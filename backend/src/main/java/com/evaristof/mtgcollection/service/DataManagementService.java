package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardFace;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallImageUris;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Backs the "Magic Data Management" screen: aggregate stats, bulk-download of
 * the collection's card images into MinIO (registering each in the scanner's
 * {@code CARD_IMAGE_HASH} reference table), and pruning MinIO images that
 * aren't part of the owned collection.
 *
 * <p>Image downloads run on a small bounded thread pool so we can process the
 * whole collection quickly without overwhelming Scryfall's image CDN. The
 * per-card DB registration is serialized (cheap) to keep JPA off multiple
 * threads.
 */
@Service
public class DataManagementService {

    private static final Logger log = LoggerFactory.getLogger(DataManagementService.class);

    private static final int DOWNLOAD_THREADS = 6;
    private static final long BATCH_THROTTLE_MS = 100;
    // Scryfall bulk dataset used by the full download. "default_cards" = every
    // (non-digital) printing, one language each — the art is language-agnostic,
    // and keeping every printing lets the footer/OCR distinguish same-art sets
    // (Alpha/Beta/Unlimited). See https://scryfall.com/docs/api/bulk-data
    private static final String BULK_DATASET = "default_cards";
    // Max concurrent image downloads + registrations during the full download.
    // Java 21 virtual threads make each in-flight download cheap, so this is a
    // politeness cap on Scryfall's image CDN — not a hardware/thread limit.
    private static final int SCRYFALL_DOWNLOAD_CONCURRENCY = 24;

    private static final java.lang.reflect.Type MAP_TYPE =
            new TypeToken<Map<String, Object>>() {}.getType();

    private final CollectionCardRepository collectionCardRepository;
    private final CardImageHashRepository hashRepository;
    private final MagicSetRepository setRepository;
    private final MinioStorageService minioStorage;
    private final CardBatchLookupService batchLookupService;
    private final CardImageMatchService matchService;
    private final OrbArtMatchService orbArtMatchService;
    private final ScryfallHttpClient scryfallClient;
    private final Gson gson;
    private final HttpClient httpClient;
    private final Object dbLock = new Object();

    public DataManagementService(CollectionCardRepository collectionCardRepository,
                                 CardImageHashRepository hashRepository,
                                 MagicSetRepository setRepository,
                                 MinioStorageService minioStorage,
                                 CardBatchLookupService batchLookupService,
                                 CardImageMatchService matchService,
                                 OrbArtMatchService orbArtMatchService,
                                 ScryfallHttpClient scryfallClient,
                                 Gson gson,
                                 HttpClient httpClient) {
        this.collectionCardRepository = collectionCardRepository;
        this.hashRepository = hashRepository;
        this.setRepository = setRepository;
        this.minioStorage = minioStorage;
        this.batchLookupService = batchLookupService;
        this.matchService = matchService;
        this.orbArtMatchService = orbArtMatchService;
        this.scryfallClient = scryfallClient;
        this.gson = gson;
        this.httpClient = httpClient;
    }

    public record DataStats(long setsInMinio, long photosInMinio,
                            long cardsInCollection, long cardsWithHash) {}

    public DataStats stats() {
        long photos = minioStorage.countAllObjects();
        long sets = minioStorage.listDistinctSetFolders().size();
        long collection = collectionCardRepository.count();
        long withHash = hashRepository.count();
        return new DataStats(sets, photos, collection, withHash);
    }

    /**
     * Rebuilds the scanner's Bag-of-Visual-Words model from all reference
     * images (vocabulary + per-card histograms). Run after bulk changes to the
     * reference set, or when the model parameters change.
     */
    public void rebuildScannerModel(DataJob job) {
        long total = hashRepository.count();
        job.setTotal((int) total);
        job.setMessage("Atualizando o modelo de reconhecimento (resumível)…");
        // Resumable/incremental: only processes cards without a histogram yet
        // (a resumed build or a newly downloaded edition), reusing the vocab.
        orbArtMatchService.updateModel((phase, done, tot) -> {
            if (tot > 0) {
                job.setTotal(tot);
                job.setProcessed(done);
            }
            job.setMessage(phase + (tot > 0 ? "… " + done + "/" + tot : "…"));
        });
        job.setMessage("Modelo atualizado para " + total + " cartas.");
    }

    // ------------------------------------------------------------------
    // Per-set operations (import / delete one edition)
    // ------------------------------------------------------------------

    /**
     * Imports every card image of a single set into MinIO + {@code card_image_hash}
     * (idempotent, skips what's already there) and then updates the scanner model
     * incrementally so the new cards' histograms are computed. Cheap enough to hit
     * the Scryfall search API directly (one set, well under the rate limit).
     */
    public void downloadSetImages(DataJob job, String setCode) {
        job.setMessage("Baixando imagens do set " + setCode + "…");
        matchService.syncImagesFromScryfall(setCode);
        job.setMessage("Atualizando o modelo de reconhecimento…");
        orbArtMatchService.updateModel((phase, done, tot) ->
                job.setMessage("Atualizando o modelo: " + phase + (tot > 0 ? " " + done + "/" + tot : "")));
        long count = hashRepository.findBySetCode(setCode).size();
        job.setMessage("Set " + setCode + " importado (" + count + " cartas).");
    }

    /** Sets registered in {@code magic_set} that are on the blacklist. */
    public List<MagicSet> listBlacklist() {
        return setRepository.findByBlacklistedTrue();
    }

    /** Flags/unflags a set as blacklisted (excluded from dropdowns + downloads). */
    public void setBlacklisted(String setCode, boolean value) {
        MagicSet set = setRepository.findById(setCode)
                .orElseThrow(() -> new java.util.NoSuchElementException("Set não encontrado: " + setCode));
        set.setBlacklisted(value);
        setRepository.save(set);
    }

    /**
     * Removes from MinIO and {@code card_image_hash} every image of every
     * blacklisted set, then invalidates the model. The sets stay blacklisted so
     * a future download won't bring them back.
     */
    public void purgeBlacklisted(DataJob job) {
        List<MagicSet> sets = setRepository.findByBlacklistedTrue();
        List<CardImageHash> hashes = new ArrayList<>();
        for (MagicSet s : sets) {
            hashes.addAll(hashRepository.findBySetCode(s.getSetCode()));
        }
        job.setTotal(hashes.size());
        job.setMessage("Removendo imagens de " + sets.size() + " set(s) da blacklist…");
        for (CardImageHash h : hashes) {
            try {
                minioStorage.deleteObject(h.getMinioPath());
            } catch (Exception e) {
                job.addError("MinIO " + h.getMinioPath() + ": " + e.getMessage());
            }
            hashRepository.delete(h);
            job.incrementProcessed();
            job.incrementSucceeded();
        }
        orbArtMatchService.invalidate();
        job.setMessage("Blacklist: removidos " + hashes.size() + " registros de " + sets.size() + " set(s).");
    }

    /**
     * Deletes every image of a set from MinIO and its {@code card_image_hash}
     * rows, then invalidates the scanner model so it reloads without them.
     */
    public void deleteSet(DataJob job, String setCode) {
        List<CardImageHash> hashes = hashRepository.findBySetCode(setCode);
        job.setTotal(hashes.size());
        for (CardImageHash h : hashes) {
            try {
                minioStorage.deleteObject(h.getMinioPath());
            } catch (Exception e) {
                job.addError("MinIO " + h.getMinioPath() + ": " + e.getMessage());
            }
            hashRepository.delete(h);
            job.incrementProcessed();
            job.incrementSucceeded();
        }
        orbArtMatchService.invalidate();
        job.setMessage("Set " + setCode + " removido: " + hashes.size() + " cartas.");
    }

    // ------------------------------------------------------------------
    // Download the whole Scryfall (via bulk data)
    // ------------------------------------------------------------------

    /**
     * Downloads the card images of every set registered in {@code magic_set}
     * into MinIO and registers them in {@code card_image_hash}.
     *
     * <p>Instead of scanning the Scryfall API set-by-set (thousands of requests
     * → HTTP 429), this pulls Scryfall's <em>bulk data</em>: one API call to
     * find the {@code default_cards} file, one download of that file, then a
     * streaming parse that already yields every card's set, number, name and PNG
     * url. {@code magic_set} acts as a whitelist of which sets to keep (skips
     * tokens/digital/promos not in the catalogue). Images are then downloaded
     * from the CDN on a bounded pool of virtual threads. Idempotent (skips what
     * is already present), so it resumes. Rebuilds the model at the end.
     * job total/processed track EDITIONS seen; succeeded/skipped = IMAGES.
     */
    public void downloadAllScryfall(DataJob job) {
        Set<String> whitelist = new HashSet<>();
        for (MagicSet set : setRepository.findAll()) {
            if (set.getSetCode() != null && !set.isBlacklisted()) {
                whitelist.add(set.getSetCode().toLowerCase(Locale.ROOT));
            }
        }
        job.setTotal(whitelist.size());
        job.setMessage("Preparando índice do que já existe…");

        // Pre-load, in ONE query each, what's already registered so the per-card
        // check is an in-memory lookup instead of a DB query + MinIO stat.
        Set<String> existingHashKeys = ConcurrentHashMap.newKeySet();
        for (Object[] row : hashRepository.findAllSetCodeAndCollectorNumber()) {
            existingHashKeys.add(hashKey((String) row[0], (String) row[1]));
        }
        Set<String> existingObjectKeys = ConcurrentHashMap.newKeySet();
        existingObjectKeys.addAll(minioStorage.listAllObjectKeys());

        Path bulkFile = null;
        try {
            job.setMessage("Localizando o catálogo bulk do Scryfall…");
            String uri = resolveBulkDownloadUri(BULK_DATASET);
            bulkFile = Files.createTempFile("scryfall-bulk-", ".json");
            job.setMessage("Baixando o catálogo do Scryfall (uma vez)…");
            downloadToFile(uri, bulkFile);
            job.setMessage("Processando o catálogo e baixando as imagens…");
            try (Reader reader = Files.newBufferedReader(bulkFile, StandardCharsets.UTF_8)) {
                processBulkCards(reader, whitelist, existingHashKeys, existingObjectKeys, job);
            }
        } catch (Exception e) {
            job.addError("Falha no download bulk: " + e.getMessage());
            log.error("Bulk download failed", e);
        } finally {
            if (bulkFile != null) {
                try {
                    Files.deleteIfExists(bulkFile);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }

        if (job.isCancelRequested()) {
            job.setStatus(DataJob.Status.CANCELLED);
            job.setMessage("Cancelado: " + job.getSucceeded() + " imagens baixadas. "
                    + "Reconstrua o modelo quando quiser (botão \"Reconstruir Modelo\").");
            return;
        }

        // Every whitelisted set has been handled — a few may simply have no cards
        // in default_cards (digital/token sets), so they were never "seen" while
        // streaming and processed stalls short of total. Snap to 100% so the UI
        // shows 1044/1044 instead of 1042/1044 (which looked like failures).
        job.setProcessed(job.getTotal());

        job.setMessage("Imagens baixadas (" + job.getSucceeded() + "). Atualizando o modelo de reconhecimento…");
        // Incremental: only the newly downloaded cards get histograms (or a full
        // build the first time). Report progress in the message so it doesn't
        // look frozen. Leaves total/processed showing the editions.
        orbArtMatchService.updateModel((phase, done, tot) ->
                job.setMessage("Atualizando o modelo: " + phase + (tot > 0 ? " " + done + "/" + tot : "")));
        int errorCount = job.getErrors().size();
        String errorNote = errorCount > 0 ? " " + errorCount + " erro(s) — veja os detalhes." : "";
        job.setMessage("Concluído: " + job.getProcessed() + " edições, " + job.getSucceeded()
                + " imagens novas, " + job.getSkipped() + " já existentes." + errorNote);
    }

    private static String hashKey(String setCode, String number) {
        return setCode + "|" + number;
    }

    /** Resolves the download URL of a Scryfall bulk-data file (1 API call). */
    private String resolveBulkDownloadUri(String type) throws IOException, InterruptedException {
        String json = scryfallClient.get("/bulk-data");
        Map<String, Object> resp = gson.fromJson(json, MAP_TYPE);
        Object dataObj = resp == null ? null : resp.get("data");
        if (dataObj instanceof List<?> data) {
            for (Object o : data) {
                if (o instanceof Map<?, ?> entry && type.equals(entry.get("type"))) {
                    Object uri = entry.get("download_uri");
                    if (uri != null) {
                        return uri.toString();
                    }
                }
            }
        }
        throw new IOException("bulk-data '" + type + "' não encontrado na resposta do Scryfall");
    }

    /** Streams a (large) file to {@code dest} on disk. */
    private void downloadToFile(String uri, Path dest) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("User-Agent", "MTGCollection/0.1")
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(dest));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("download do bulk falhou: HTTP " + response.statusCode());
        }
    }

    /**
     * Streams the bulk-data JSON array and, for each card whose set is in the
     * whitelist, dispatches an image download + registration to a bounded pool
     * of virtual threads. Package-private so it can be unit-tested with a small
     * in-memory array (no network). {@code processed} counts distinct sets seen.
     */
    void processBulkCards(Reader bulkJson, Set<String> whitelist,
                          Set<String> existingHashKeys, Set<String> existingObjectKeys, DataJob job) {
        Semaphore downloadPermits = new Semaphore(SCRYFALL_DOWNLOAD_CONCURRENCY);
        ExecutorService cardPool = Executors.newVirtualThreadPerTaskExecutor();
        Set<String> setsSeen = ConcurrentHashMap.newKeySet();
        try (JsonReader reader = new JsonReader(bulkJson)) {
            reader.beginArray();
            while (reader.hasNext()) {
                if (job.isCancelRequested()) {
                    break;
                }
                Map<String, Object> card = gson.fromJson(reader, MAP_TYPE);
                Object setObj = card.get("set");
                if (!(setObj instanceof String s)) {
                    continue;
                }
                String setCode = s.toLowerCase(Locale.ROOT);
                if (!whitelist.contains(setCode)) {
                    continue; // token / digital / promo set not in the catalogue
                }
                if (setsSeen.add(setCode)) {
                    job.incrementProcessed();
                }
                // Backpressure: block the parser until a download slot frees, so
                // we never hold more than the permit count of card objects live.
                downloadPermits.acquire();
                cardPool.submit(() -> {
                    try {
                        downloadBulkCard(card, setCode, job, existingHashKeys, existingObjectKeys);
                    } catch (Exception e) {
                        job.addError(setCode + ": " + e.getMessage());
                    } finally {
                        downloadPermits.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.addError("Processamento interrompido");
        } catch (Exception e) {
            job.addError("Erro ao processar o catálogo bulk: " + e.getMessage());
            log.error("Bulk parse failed", e);
        } finally {
            cardPool.shutdown();
            try {
                cardPool.awaitTermination(6, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Downloads and registers one card described by a bulk-data card object. */
    private void downloadBulkCard(Map<String, Object> card, String setCode, DataJob job,
                                  Set<String> existingHashKeys, Set<String> existingObjectKeys)
            throws IOException {
        if (job.isCancelRequested()) {
            return;
        }
        String name = (String) card.get("name");
        String number = (String) card.get("collector_number");
        if (number == null) {
            return;
        }
        String pngUrl = pngFromMap(card);
        if (pngUrl == null) {
            return; // some layouts have no single PNG — nothing to store
        }
        Object setNameObj = card.get("set_name");
        String setName = setNameObj instanceof String sn ? sn : setCode;
        String objectKey = minioStorage.objectKey(setName, setCode, number, name != null ? name : "Unknown");

        boolean hasHash = existingHashKeys.contains(hashKey(setCode, number));
        boolean existsInMinio = existingObjectKeys.contains(objectKey);
        if (existsInMinio && hasHash) {
            job.incrementSkipped();
            return;
        }
        byte[] bytes = existsInMinio ? minioStorage.download(objectKey) : downloadImage(pngUrl);
        if (!existsInMinio) {
            minioStorage.upload(objectKey, bytes);
            existingObjectKeys.add(objectKey);
        }
        if (!hasHash) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IOException("imagem não pôde ser decodificada");
            }
            // deferHistogram=true: the BoVW histogram is globally locked on the
            // OpenCV lock and recomputed for every card by the final rebuild, so
            // skip it here. No dbLock: the unique (set, number) constraint guards
            // against duplicates.
            matchService.registerHashIfAbsent(setCode, number,
                    name != null ? name : "Unknown", objectKey, image, true);
            existingHashKeys.add(hashKey(setCode, number));
        }
        job.incrementSucceeded();
    }

    private String pngFromMap(Map<String, Object> cardMap) {
        @SuppressWarnings("unchecked")
        Map<String, String> imageUris = (Map<String, String>) cardMap.get("image_uris");
        if (imageUris != null && imageUris.get("png") != null) {
            return imageUris.get("png");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> faces = (List<Map<String, Object>>) cardMap.get("card_faces");
        if (faces != null && !faces.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, String> faceUris = (Map<String, String>) faces.get(0).get("image_uris");
            if (faceUris != null && faceUris.get("png") != null) {
                return faceUris.get("png");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Download collection images
    // ------------------------------------------------------------------

    /**
     * For every distinct set+number in the collection, ensures the card image
     * is in MinIO and registered in {@code CARD_IMAGE_HASH}. Idempotent:
     * cards already present are skipped. Updates {@code job} progress.
     */
    public void downloadCollectionImages(DataJob job) {
        // Distinct set+number (a collection has multiple rows per card: foil,
        // language, quantity stacks) — one image suffices per printing.
        List<ScryfallCardIdentifier> identifiers = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (CollectionCard card : collectionCardRepository.findAll()) {
            String setCode = card.getSetCode();
            String number = card.getCardNumber();
            if (setCode == null || setCode.isBlank() || number == null || number.isBlank()) {
                continue;
            }
            String key = ownedKey(setCode, number);
            if (seen.putIfAbsent(key, Boolean.TRUE) == null) {
                identifiers.add(ScryfallCardIdentifier.bySetAndNumber(setCode.trim(), number.trim()));
            }
        }

        job.setTotal(identifiers.size());
        job.setMessage("Buscando dados de " + identifiers.size() + " cartas no Scryfall…");
        if (identifiers.isEmpty()) {
            return;
        }

        List<ScryfallCardIdentifier> notFound = new ArrayList<>();
        List<ScryfallCard> cards = batchLookupService.getCardsBatch(identifiers, BATCH_THROTTLE_MS, notFound);
        for (ScryfallCardIdentifier nf : notFound) {
            job.addError("Não encontrado no Scryfall: " + nf.getSet() + "/" + nf.getCollectorNumber());
        }

        job.setMessage("Baixando imagens…");
        boolean created = processCardsIntoMinio(cards, job);
        if (created) {
            orbArtMatchService.invalidate();
        }
        job.setMessage("Concluído: " + job.getSucceeded() + " novas, " + job.getSkipped()
                + " já existentes, " + job.getErrors().size() + " erros.");
    }

    /**
     * Downloads + stores + hashes each Scryfall card into MinIO in parallel.
     * Returns true if at least one new hash row was created.
     */
    private boolean processCardsIntoMinio(List<ScryfallCard> cards, DataJob job) {
        ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS);
        Map<Long, Boolean> createdFlag = new ConcurrentHashMap<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ScryfallCard card : cards) {
                futures.add(pool.submit(() -> {
                    try {
                        if (processOneCard(card)) {
                            createdFlag.put(1L, Boolean.TRUE);
                            job.incrementSucceeded();
                        } else {
                            job.incrementSkipped();
                        }
                    } catch (Exception e) {
                        job.addError(card.getSet() + "/" + card.getCollectorNumber() + ": " + e.getMessage());
                        log.warn("Failed to store image for {}/{}: {}",
                                card.getSet(), card.getCollectorNumber(), e.getMessage());
                    } finally {
                        job.incrementProcessed();
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("Download task failed: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return !createdFlag.isEmpty();
    }

    /**
     * Ensures one card's image is in MinIO and hashed. Returns true if a new
     * hash row was created (i.e. this was genuinely new reference data).
     */
    private boolean processOneCard(ScryfallCard card) throws IOException {
        String setCode = card.getSet();
        String number = card.getCollectorNumber();
        String name = card.getName() != null ? card.getName() : "Unknown";
        if (setCode == null || number == null) {
            return false;
        }

        String setName = resolveSetName(setCode);
        String objectKey = minioStorage.objectKey(setName, setCode, number, name);

        boolean existsInMinio = minioStorage.exists(objectKey);
        boolean hasHash;
        synchronized (dbLock) {
            hasHash = hashRepository.findBySetCodeAndCollectorNumber(setCode, number).isPresent();
        }
        if (existsInMinio && hasHash) {
            return false;
        }

        byte[] bytes;
        if (existsInMinio) {
            bytes = minioStorage.download(objectKey);
        } else {
            String pngUrl = resolvePngUrl(card);
            if (pngUrl == null) {
                throw new IOException("sem image_uris.png");
            }
            bytes = downloadImage(pngUrl);
            minioStorage.upload(objectKey, bytes);
        }

        if (!hasHash) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IOException("imagem não pôde ser decodificada");
            }
            synchronized (dbLock) {
                return matchService.registerHashIfAbsent(setCode, number, name, objectKey, image);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Prune images outside the collection
    // ------------------------------------------------------------------

    /**
     * Deletes from MinIO every image that doesn't correspond to a card in the
     * collection, and removes the matching orphan {@code CARD_IMAGE_HASH} rows.
     */
    public void pruneOutsideCollection(DataJob job) {
        Set<String> owned = new java.util.HashSet<>();
        for (CollectionCard card : collectionCardRepository.findAll()) {
            if (card.getSetCode() != null && card.getCardNumber() != null) {
                owned.add(ownedKey(card.getSetCode(), card.getCardNumber()));
            }
        }

        List<String> keys = minioStorage.listAllObjectKeys();
        job.setTotal(keys.size());
        job.setMessage("Removendo imagens fora da coleção…");

        for (String objectKey : keys) {
            try {
                String[] parsed = matchService.parseSetAndNumber(objectKey);
                boolean keep = parsed != null && owned.contains(ownedKey(parsed[0], parsed[1]));
                if (!keep) {
                    minioStorage.deleteObject(objectKey);
                    if (parsed != null) {
                        hashRepository.findBySetCodeAndCollectorNumber(parsed[0], parsed[1])
                                .ifPresent(hashRepository::delete);
                    }
                    job.incrementSucceeded();
                } else {
                    job.incrementSkipped();
                }
            } catch (Exception e) {
                job.addError(objectKey + ": " + e.getMessage());
            } finally {
                job.incrementProcessed();
            }
        }

        // The reference set changed — rebuild the ORB cache on the next scan.
        orbArtMatchService.invalidate();
        job.setMessage("Concluído: " + job.getSucceeded() + " removidas, "
                + job.getSkipped() + " mantidas.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String ownedKey(String setCode, String number) {
        return setCode.trim().toLowerCase(Locale.ROOT) + "|"
                + number.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveSetName(String setCode) {
        return setRepository.findById(setCode)
                .map(MagicSet::getSetName)
                .orElse(setCode);
    }

    private String resolvePngUrl(ScryfallCard card) {
        ScryfallImageUris top = card.getImageUris();
        if (top != null && top.getPng() != null) {
            return top.getPng();
        }
        List<ScryfallCardFace> faces = card.getCardFaces();
        if (faces != null && !faces.isEmpty()) {
            ScryfallImageUris faceUris = faces.get(0).getImageUris();
            if (faceUris != null && faceUris.getPng() != null) {
                return faceUris.getPng();
            }
        }
        return null;
    }

    private byte[] downloadImage(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "MTGCollection/0.1")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " para " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrompido: " + url, e);
        }
    }
}
