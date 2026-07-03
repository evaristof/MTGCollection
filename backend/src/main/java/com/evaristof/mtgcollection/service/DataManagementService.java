package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardFace;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallImageUris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private final CollectionCardRepository collectionCardRepository;
    private final CardImageHashRepository hashRepository;
    private final MagicSetRepository setRepository;
    private final MinioStorageService minioStorage;
    private final CardBatchLookupService batchLookupService;
    private final CardImageMatchService matchService;
    private final OrbArtMatchService orbArtMatchService;
    private final HttpClient httpClient;
    private final Object dbLock = new Object();

    public DataManagementService(CollectionCardRepository collectionCardRepository,
                                 CardImageHashRepository hashRepository,
                                 MagicSetRepository setRepository,
                                 MinioStorageService minioStorage,
                                 CardBatchLookupService batchLookupService,
                                 CardImageMatchService matchService,
                                 OrbArtMatchService orbArtMatchService,
                                 HttpClient httpClient) {
        this.collectionCardRepository = collectionCardRepository;
        this.hashRepository = hashRepository;
        this.setRepository = setRepository;
        this.minioStorage = minioStorage;
        this.batchLookupService = batchLookupService;
        this.matchService = matchService;
        this.orbArtMatchService = orbArtMatchService;
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
        job.setMessage("Reconstruindo o modelo de reconhecimento (BoVW)…");
        orbArtMatchService.rebuild();
        job.setMessage("Modelo reconstruído para " + total + " cartas.");
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
