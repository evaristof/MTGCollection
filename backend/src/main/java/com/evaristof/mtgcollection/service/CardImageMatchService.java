package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CardImageMatchService {

    // OpenCV native library is loaded once, by CardPerspectiveService's own
    // static initializer — loading it a second time from here corrupted
    // native memory (same class of bug as the DJL/ONNX conflict described
    // in CnnEmbeddingService). CardPerspectiveService is a required
    // constructor dependency below, so its class (and static initializer)
    // is guaranteed to load before this class is used.

    private static final Logger log = LoggerFactory.getLogger(CardImageMatchService.class);
    private static final int HASH_BIT_RESOLUTION = 64;
    // Minimum RANSAC homography inliers for an ORB art match to be trusted.
    // Correct matches on real photos land >=20 inliers; wrong cards stay in
    // the low single digits, so this cleanly separates them with margin.
    private static final int ORB_ACCEPT_INLIERS = 12;

    // Title/footer OCR crop regions (as a fraction of the perspective-corrected
    // card image), calibrated against the standard modern Magic frame
    // (~2015+). Cards with special/vintage borders won't line up here — OCR
    // simply returns no usable text for them and the pipeline falls back to
    // pHash/ORB, so keeping fixed percentages is safe even though they aren't
    // universal across every frame era.
    private static final double TITLE_TOP = 0.052;
    private static final double TITLE_BOTTOM = 0.097;
    private static final double TITLE_LEFT = 0.055;
    private static final double TITLE_RIGHT = 0.85;
    private static final double FOOTER_TOP = 0.936;
    private static final double FOOTER_BOTTOM = 0.978;
    private static final double FOOTER_LEFT = 0.015;
    private static final double FOOTER_RIGHT = 0.24;
    private static final int OCR_CROP_UPSCALE = 3;
    // Empirically, fuzzy-matching pure OCR noise against the ~35k-name
    // Scryfall catalog still lands a "match" around score 86 by pure
    // coincidence — only trust scores at or above this as a real signal.
    private static final int NAME_MATCH_STRONG_THRESHOLD = 90;

    private static final Pattern COLLECTOR_NUMBER_PATTERN = Pattern.compile("(\\d{1,4})(?:/\\d{1,4})?");
    private static final Pattern SET_CODE_PATTERN = Pattern.compile("\\b([A-Z]{2,5})\\b");
    private static final Set<String> FOOTER_SET_CODE_STOPWORDS = Set.of("EN", "R", "U", "C", "M");

    private final CardImageHashRepository hashRepository;
    private final MagicSetRepository setRepository;
    private final MinioStorageService minioStorage;
    private final ScryfallHttpClient scryfallClient;
    private final CnnEmbeddingService cnnEmbeddingService;
    private final CardPerspectiveService perspectiveService;
    private final TesseractOcrService ocrService;
    private final CardNameCatalogService nameCatalogService;
    private final OrbArtMatchService orbArtMatchService;
    private final Gson gson;
    private final HttpClient imageHttpClient;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final AtomicBoolean populateRunning = new AtomicBoolean(false);

    public record MatchResult(CardImageHash card, double confidence) {}

    private record FooterInfo(String collectorNumber, String setCode) {}

    private record OcrPass(CardNameCatalogService.NameMatch nameMatch, FooterInfo footer) {}

    private record TextSignal(BufferedImage image, CardNameCatalogService.NameMatch nameMatch, FooterInfo footer) {}

    public CardImageMatchService(CardImageHashRepository hashRepository,
                                 MagicSetRepository setRepository,
                                 MinioStorageService minioStorage,
                                 ScryfallHttpClient scryfallClient,
                                 CnnEmbeddingService cnnEmbeddingService,
                                 CardPerspectiveService perspectiveService,
                                 TesseractOcrService ocrService,
                                 CardNameCatalogService nameCatalogService,
                                 OrbArtMatchService orbArtMatchService,
                                 Gson gson) {
        this.hashRepository = hashRepository;
        this.setRepository = setRepository;
        this.minioStorage = minioStorage;
        this.scryfallClient = scryfallClient;
        this.cnnEmbeddingService = cnnEmbeddingService;
        this.perspectiveService = perspectiveService;
        this.ocrService = ocrService;
        this.nameCatalogService = nameCatalogService;
        this.orbArtMatchService = orbArtMatchService;
        this.gson = gson;
        this.imageHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PreDestroy
    void closeHttpClient() {
        imageHttpClient.close();
    }

    public String computeHash(BufferedImage image) {
        PerceptiveHash hasher = new PerceptiveHash(HASH_BIT_RESOLUTION);
        Hash hash = hasher.hash(image);
        return hash.getHashValue().toString(16);
    }

    public MatchResult findBestMatch(BufferedImage uploadedImage) {
        List<CardImageHash> allHashes = hashRepository.findAll();
        if (allHashes.isEmpty()) {
            return null;
        }

        // 1. Correct perspective — downstream art/OCR crops assume a flat,
        // border-cropped card. Fails open (returns the original) if no card
        // contour is found.
        BufferedImage corrected = perspectiveService.correctPerspective(uploadedImage);

        // 2. OCR the title/footer bands (strong, deterministic signal when the
        // frame/language line up), trying both orientations.
        TextSignal textSignal = extractTextSignal(corrected);
        BufferedImage workingImage = textSignal.image();

        CardNameCatalogService.NameMatch nameMatch = textSignal.nameMatch();
        CardImageHash nameCard = null;
        if (nameMatch != null && nameMatch.score() >= NAME_MATCH_STRONG_THRESHOLD) {
            nameCard = allHashes.stream()
                    .filter(h -> h.getCardName().equalsIgnoreCase(nameMatch.name()))
                    .findFirst().orElse(null);
        }

        // 3. ORB art match against ALL cached references — the primary,
        // language- and frame-independent signal. Ranked by homography
        // inliers (geometric verification), highest first.
        List<OrbArtMatchService.ScoredCard> ranked = orbArtMatchService.match(workingImage);
        OrbArtMatchService.ScoredCard bestOrb = ranked.isEmpty() ? null : ranked.get(0);
        boolean orbStrong = bestOrb != null && bestOrb.inliers() >= ORB_ACCEPT_INLIERS;

        CardImageHash chosen;
        double confidence;
        String reason;

        boolean nameOnOrbTop = nameCard != null && bestOrb != null
                && nameCard.getId().equals(bestOrb.card().getId());
        boolean footerOnName = nameCard != null && footerMatches(textSignal.footer(), nameCard);

        if (orbStrong) {
            // Geometric art match — trust it. Corroborating OCR name/footer
            // pushes confidence toward certainty.
            chosen = bestOrb.card();
            confidence = 0.60 + Math.min(0.30, bestOrb.inliers() / 60.0);
            if (nameOnOrbTop) confidence += 0.08;
            if (footerOnName) confidence += 0.05;
            reason = "orb(inliers=" + bestOrb.inliers() + ",good=" + bestOrb.goodMatches() + ")";
        } else if (nameCard != null && footerOnName) {
            // No strong art match, but the printed name AND collector number/
            // set were read and agree — reliable for legible modern frames.
            chosen = nameCard;
            confidence = 0.88;
            reason = "name+footer";
        } else if (nameCard != null && nameOnOrbTop) {
            chosen = nameCard;
            confidence = 0.70;
            reason = "name+orbtop";
        } else {
            chosen = null;
            confidence = 0.0;
            reason = "none";
        }

        int bestInliers = bestOrb != null ? bestOrb.inliers() : 0;
        int bestGood = bestOrb != null ? bestOrb.goodMatches() : 0;
        log.info("Scan: chosen={} reason={} conf={} | orbTop={} inliers={} good={} | ocrName={}",
                chosen == null ? "none" : chosen.getSetCode() + "/" + chosen.getCollectorNumber()
                        + " (" + chosen.getCardName() + ")",
                reason, String.format("%.2f", confidence),
                bestOrb == null ? "-" : bestOrb.card().getSetCode() + "/" + bestOrb.card().getCollectorNumber(),
                bestInliers, bestGood, nameMatch);

        if (chosen == null) {
            return null;
        }
        double roundedConfidence = Math.round(Math.min(1.0, confidence) * 100.0) / 100.0;
        return new MatchResult(chosen, roundedConfidence);
    }

    /**
     * Diagnostic probe (not part of normal matching): given a photo and the
     * KNOWN-correct set/number, reports where the expected card lands in the
     * ORB art-match ranking (its rank, inliers, good matches) plus the OCR
     * read — to pinpoint why a real photo does or doesn't match.
     */
    public Map<String, Object> diagnose(BufferedImage uploadedImage, String expectedSet, String expectedNumber) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<CardImageHash> allHashes = hashRepository.findAll();
        out.put("db_size", allHashes.size());

        BufferedImage corrected = perspectiveService.correctPerspective(uploadedImage);
        boolean perspectiveApplied = corrected.getWidth() != uploadedImage.getWidth()
                || corrected.getHeight() != uploadedImage.getHeight();
        out.put("perspective_applied", perspectiveApplied);

        TextSignal textSignal = extractTextSignal(corrected);
        out.put("ocr_name_match", String.valueOf(textSignal.nameMatch()));
        out.put("ocr_footer", String.valueOf(textSignal.footer()));

        List<OrbArtMatchService.ScoredCard> ranked = orbArtMatchService.match(textSignal.image());
        if (!ranked.isEmpty()) {
            OrbArtMatchService.ScoredCard top = ranked.get(0);
            out.put("orb_top", top.card().getSetCode() + "/" + top.card().getCollectorNumber()
                    + " (" + top.card().getCardName() + ") inliers=" + top.inliers()
                    + " good=" + top.goodMatches());
        }

        CardImageHash expected = allHashes.stream()
                .filter(h -> h.getSetCode().equalsIgnoreCase(expectedSet)
                        && h.getCollectorNumber().equalsIgnoreCase(expectedNumber))
                .findFirst().orElse(null);
        if (expected == null) {
            out.put("expected_in_db", false);
            return out;
        }
        out.put("expected_in_db", true);
        out.put("expected_name", expected.getCardName());
        // Stage-1: where does the correct card land in the BoVW shortlist
        // ordering? This is the gate — if it's beyond the shortlist size, ORB
        // never sees it.
        out.put("expected_shortlist_rank",
                orbArtMatchService.bovwShortlistRank(textSignal.image(), expectedSet, expectedNumber));

        int rank = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).card().getId().equals(expected.getId())) {
                rank = i;
                out.put("expected_orb_rank", i);
                out.put("expected_orb_inliers", ranked.get(i).inliers());
                out.put("expected_orb_good", ranked.get(i).goodMatches());
                break;
            }
        }
        if (rank < 0) {
            out.put("expected_orb_rank", "not scored (no descriptors cached / no matches)");
        }

        MatchResult result = findBestMatch(uploadedImage);
        out.put("actual_match", result == null ? "none"
                : result.card().getSetCode() + "/" + result.card().getCollectorNumber()
                        + " (" + result.card().getCardName() + ") conf=" + result.confidence());
        return out;
    }

    private TextSignal extractTextSignal(BufferedImage corrected) {
        if (!ocrService.isAvailable()) {
            return new TextSignal(corrected, null, null);
        }

        OcrPass normalPass = runOcrPass(corrected);
        int normalScore = normalPass.nameMatch() != null ? normalPass.nameMatch().score() : 0;
        if (normalScore >= NAME_MATCH_STRONG_THRESHOLD) {
            return new TextSignal(corrected, normalPass.nameMatch(), normalPass.footer());
        }

        // Geometry alone can't tell "right side up" from "upside down" (a
        // card rotated 90 degrees in the source photo warps to a portrait
        // rectangle either way) — try the title OCR on the 180-flipped image
        // too and use whichever orientation actually reads a real name.
        BufferedImage flipped = rotate180(corrected);
        OcrPass flippedPass = runOcrPass(flipped);
        int flippedScore = flippedPass.nameMatch() != null ? flippedPass.nameMatch().score() : 0;
        if (flippedScore > normalScore && flippedScore >= NAME_MATCH_STRONG_THRESHOLD) {
            return new TextSignal(flipped, flippedPass.nameMatch(), flippedPass.footer());
        }

        // Neither orientation produced a confident OCR read — keep the
        // perspective-corrected image as-is and let pHash/ORB (which
        // tolerate rotation reasonably well) carry the match.
        return new TextSignal(corrected, null, null);
    }

    private OcrPass runOcrPass(BufferedImage image) {
        BufferedImage titleCrop = upscaleCrop(image, TITLE_TOP, TITLE_BOTTOM, TITLE_LEFT, TITLE_RIGHT);
        String titleText = ocrService.recognize(titleCrop, "7").orElse("");
        List<CardNameCatalogService.NameMatch> matches = nameCatalogService.fuzzyMatch(titleText, 1);
        CardNameCatalogService.NameMatch best = matches.isEmpty() ? null : matches.get(0);

        BufferedImage footerCrop = upscaleCrop(image, FOOTER_TOP, FOOTER_BOTTOM, FOOTER_LEFT, FOOTER_RIGHT);
        String footerText = ocrService.recognize(footerCrop, "6").orElse("");
        FooterInfo footer = parseFooter(footerText);

        log.debug("OCR pass: title='{}' -> {}; footer='{}' -> {}",
                titleText, best, footerText.replace("\n", " | "), footer);
        return new OcrPass(best, footer);
    }

    private FooterInfo parseFooter(String footerText) {
        if (footerText == null || footerText.isBlank()) {
            return null;
        }
        String number = null;
        Matcher numberMatcher = COLLECTOR_NUMBER_PATTERN.matcher(footerText);
        if (numberMatcher.find()) {
            number = numberMatcher.group(1);
        }
        String setCode = null;
        Matcher setMatcher = SET_CODE_PATTERN.matcher(footerText.toUpperCase(Locale.ROOT));
        while (setMatcher.find()) {
            String candidate = setMatcher.group(1);
            if (!FOOTER_SET_CODE_STOPWORDS.contains(candidate)) {
                setCode = candidate;
                break;
            }
        }
        if (number == null || setCode == null) {
            return null;
        }
        return new FooterInfo(number, setCode);
    }

    private boolean footerMatches(FooterInfo footer, CardImageHash card) {
        if (footer == null) {
            return false;
        }
        return stripLeadingZeros(footer.collectorNumber()).equals(stripLeadingZeros(card.getCollectorNumber()))
                && footer.setCode().equalsIgnoreCase(card.getSetCode());
    }

    private String stripLeadingZeros(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("^0+(?=\\d)", "").toLowerCase(Locale.ROOT);
    }

    private BufferedImage upscaleCrop(BufferedImage image, double top, double bottom, double left, double right) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = Math.max(0, (int) (w * left));
        int x1 = Math.min(w, (int) (w * right));
        int y0 = Math.max(0, (int) (h * top));
        int y1 = Math.min(h, (int) (h * bottom));
        BufferedImage sub = image.getSubimage(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));

        int newW = sub.getWidth() * OCR_CROP_UPSCALE;
        int newH = sub.getHeight() * OCR_CROP_UPSCALE;
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(sub, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }

    private BufferedImage rotate180(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.rotate(Math.PI, w / 2.0, h / 2.0);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return out;
    }

    public Optional<CardImageHash> findHashBySetAndNumber(String setCode, String collectorNumber) {
        return hashRepository.findBySetCodeAndCollectorNumber(setCode, collectorNumber);
    }

    @Async
    public void syncImagesFromScryfallAsync(String setCode) {
        if (!syncRunning.compareAndSet(false, true)) {
            log.warn("Sync already running, ignoring request for set: {}", setCode);
            return;
        }
        try {
            syncImagesFromScryfall(setCode);
        } finally {
            syncRunning.set(false);
        }
    }

    @Async
    public void populateHashesFromMinioAsync() {
        if (!populateRunning.compareAndSet(false, true)) {
            log.warn("Populate already running, ignoring request");
            return;
        }
        try {
            populateHashesFromMinio();
        } finally {
            populateRunning.set(false);
        }
    }

    public int populateHashesFromMinio() {
        List<String> objectKeys = minioStorage.listAllObjectKeys();
        int count = 0;

        for (String objectKey : objectKeys) {
            try {
                ParsedObjectKey parsed = parseObjectKey(objectKey);
                if (parsed == null) {
                    log.debug("Skipping unparseable key: {}", objectKey);
                    continue;
                }

                if (hashRepository.findBySetCodeAndCollectorNumber(
                        parsed.setCode, parsed.collectorNumber).isPresent()) {
                    log.debug("Hash already exists for {}/{}, skipping", parsed.setCode, parsed.collectorNumber);
                    continue;
                }

                byte[] imageData = minioStorage.download(objectKey);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
                if (image == null) {
                    log.warn("Could not decode image for key: {}", objectKey);
                    continue;
                }

                if (registerHashIfAbsent(parsed.setCode, parsed.collectorNumber,
                        parsed.cardName, objectKey, image)) {
                    count++;
                    log.info("Populated hash for {}/{} ({})", parsed.setCode, parsed.collectorNumber, parsed.cardName);
                }
            } catch (Exception e) {
                log.warn("Failed to process MinIO object '{}': {}", objectKey, e.getMessage());
            }
        }

        log.info("Populated {} hashes from MinIO bucket", count);
        if (count > 0) {
            // New reference cards were added — drop the ORB descriptor cache so
            // it's rebuilt (including the new cards) on the next scan.
            orbArtMatchService.invalidate();
        }
        return count;
    }

    /**
     * Registers a {@link CardImageHash} row for the given card if one doesn't
     * already exist for its set + collector number. Returns true when a new
     * row was created. Deliberately does NOT invalidate the ORB descriptor
     * cache — a caller populating many cards in a batch should call
     * {@link OrbArtMatchService#invalidate()} once at the end.
     */
    public boolean registerHashIfAbsent(String setCode, String collectorNumber, String cardName,
                                        String objectKey, BufferedImage image) {
        if (hashRepository.findBySetCodeAndCollectorNumber(setCode, collectorNumber).isPresent()) {
            return false;
        }
        CardImageHash entity = new CardImageHash();
        entity.setSetCode(setCode);
        entity.setCollectorNumber(collectorNumber);
        entity.setCardName(cardName);
        entity.setPHash(computeHash(image));
        entity.setMinioPath(objectKey);
        computeAndSetEmbedding(entity, image);
        hashRepository.save(entity);
        return true;
    }

    /**
     * Extracts {@code [setCode, collectorNumber]} from a MinIO object key, or
     * {@code null} if it doesn't follow the naming convention. Reusable by
     * other services (e.g. pruning images that aren't in the collection).
     */
    public String[] parseSetAndNumber(String objectKey) {
        ParsedObjectKey parsed = parseObjectKey(objectKey);
        return parsed == null ? null : new String[] {parsed.setCode(), parsed.collectorNumber()};
    }

    private record ParsedObjectKey(String setCode, String collectorNumber, String cardName) {}

    private ParsedObjectKey parseObjectKey(String objectKey) {
        int slashIdx = objectKey.indexOf('/');
        if (slashIdx < 0) {
            return null;
        }

        String folder = objectKey.substring(0, slashIdx);
        String file = objectKey.substring(slashIdx + 1);

        int dashSpaceIdx = folder.lastIndexOf(" - ");
        if (dashSpaceIdx < 0) {
            return null;
        }
        String setCode = folder.substring(dashSpaceIdx + 3).trim();
        if (setCode.isEmpty()) {
            return null;
        }

        String fileWithoutExt = file.replaceFirst("\\.[^.]+$", "");
        int splitIdx = findCollectorNameSplit(fileWithoutExt);
        if (splitIdx < 0) {
            return null;
        }
        String collectorNumber = fileWithoutExt.substring(0, splitIdx);
        String cardName = fileWithoutExt.substring(splitIdx + 1);
        if (collectorNumber.isEmpty() || cardName.isEmpty()) {
            return null;
        }
        return new ParsedObjectKey(setCode, collectorNumber, cardName);
    }

    private int findCollectorNameSplit(String fileStem) {
        for (int i = 0; i < fileStem.length(); i++) {
            if (fileStem.charAt(i) == '-' && i + 1 < fileStem.length()
                    && Character.isLetter(fileStem.charAt(i + 1))) {
                return i;
            }
        }
        int firstDash = fileStem.indexOf('-');
        if (firstDash > 0 && firstDash < fileStem.length() - 1) {
            return firstDash;
        }
        return -1;
    }

    public void syncImagesFromScryfall(String setCode) {
        try {
            String searchPath = "/cards/search?q=" +
                    URLEncoder.encode("set:" + setCode, StandardCharsets.UTF_8) +
                    "&unique=prints";

            String currentPage = searchPath;
            while (currentPage != null) {
                currentPage = processSearchPage(currentPage, setCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sync interrupted for set: " + setCode, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sync images for set: " + setCode, e);
        }
    }

    private String processSearchPage(String path, String setCode) throws IOException, InterruptedException {
        String json = scryfallClient.get(path);
        Map<String, Object> response = gson.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null) {
            return null;
        }

        for (Map<String, Object> cardMap : data) {
            try {
                processCard(cardMap, setCode);
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("Failed to process card: {}", e.getMessage());
            }
        }

        Boolean hasMore = (Boolean) response.get("has_more");
        String nextPage = (String) response.get("next_page");
        if (Boolean.TRUE.equals(hasMore) && nextPage != null) {
            Thread.sleep(100);
            return nextPage;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void processCard(Map<String, Object> cardMap, String setCode) throws IOException, InterruptedException {
        String name = (String) cardMap.get("name");
        String collectorNumber = (String) cardMap.get("collector_number");
        String setName = (String) cardMap.get("set_name");

        if (hashRepository.findBySetCodeAndCollectorNumber(setCode, collectorNumber).isPresent()) {
            log.debug("Hash already exists for {}/{}, skipping", setCode, collectorNumber);
            return;
        }

        Map<String, String> imageUris = (Map<String, String>) cardMap.get("image_uris");
        if (imageUris == null || !imageUris.containsKey("png")) {
            List<Map<String, Object>> cardFaces = (List<Map<String, Object>>) cardMap.get("card_faces");
            if (cardFaces != null && !cardFaces.isEmpty()) {
                Map<String, String> faceUris = (Map<String, String>) cardFaces.get(0).get("image_uris");
                if (faceUris != null && faceUris.containsKey("png")) {
                    imageUris = faceUris;
                }
            }
            if (imageUris == null || !imageUris.containsKey("png")) {
                log.debug("No image_uris.png for {}/{}, skipping", setCode, collectorNumber);
                return;
            }
        }

        String imageUrl = imageUris.get("png");
        byte[] imageData = downloadImage(imageUrl);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
        if (image == null) {
            log.warn("Could not decode image for {}/{}", setCode, collectorNumber);
            return;
        }

        String resolvedSetName = setName != null ? setName : resolveSetName(setCode);
        String objectKey = minioStorage.objectKey(
                resolvedSetName, setCode, collectorNumber,
                name != null ? name : "Unknown");
        minioStorage.upload(objectKey, imageData);

        String hash = computeHash(image);
        CardImageHash entity = new CardImageHash();
        entity.setSetCode(setCode);
        entity.setCollectorNumber(collectorNumber);
        entity.setCardName(name != null ? name : "Unknown");
        entity.setPHash(hash);
        entity.setMinioPath(objectKey);
        computeAndSetEmbedding(entity, image);
        hashRepository.save(entity);

        log.info("Synced image hash for {}/{} ({})", setCode, collectorNumber, name);
    }

    private String resolveSetName(String setCode) {
        return setRepository.findById(setCode)
                .map(MagicSet::getSetName)
                .orElse(setCode);
    }

    private byte[] downloadImage(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "MTGCollection/0.1")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response = imageHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Image download failed: " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private void computeAndSetEmbedding(CardImageHash entity, BufferedImage image) {
        // CNN embedding disabled: DJL/ONNX Runtime native libs conflict with OpenCV on Windows.
        // CNN embeddings can be populated by an external Python/CLI tool if needed.
    }

}
