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
import nu.pattern.OpenCV;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CardImageMatchService {

    static {
        OpenCV.loadLocally();
    }

    private static final Logger log = LoggerFactory.getLogger(CardImageMatchService.class);
    private static final int HASH_BIT_RESOLUTION = 64;
    private static final int PHASH_SHORTLIST_SIZE = 25;
    private static final double PHASH_SHORTLIST_DISTANCE = 0.50;
    private static final double PHASH_DIRECT_MATCH_DISTANCE = 0.10;
    private static final int ORB_FEATURES = 1500;
    private static final float ORB_RATIO_TEST = 0.75f;
    private static final int MIN_ORB_GOOD_MATCHES = 10;
    private static final int MIN_ORB_INLIERS = 8;
    private static final double MIN_HYBRID_CONFIDENCE = 0.35;
    private static final int ORB_TARGET_HEIGHT = 700;

    private final CardImageHashRepository hashRepository;
    private final MagicSetRepository setRepository;
    private final MinioStorageService minioStorage;
    private final ScryfallHttpClient scryfallClient;
    private final CnnEmbeddingService cnnEmbeddingService;
    private final Gson gson;
    private final HttpClient imageHttpClient;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final AtomicBoolean populateRunning = new AtomicBoolean(false);

    public record MatchResult(CardImageHash card, double confidence) {}

    private record Candidate(CardImageHash card, double pHashDistance) {}

    private record OrbValidationResult(double score, int goodMatches, int inliers) {}

    public CardImageMatchService(CardImageHashRepository hashRepository,
                                 MagicSetRepository setRepository,
                                 MinioStorageService minioStorage,
                                 ScryfallHttpClient scryfallClient,
                                 CnnEmbeddingService cnnEmbeddingService,
                                 Gson gson) {
        this.hashRepository = hashRepository;
        this.setRepository = setRepository;
        this.minioStorage = minioStorage;
        this.scryfallClient = scryfallClient;
        this.cnnEmbeddingService = cnnEmbeddingService;
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

        PerceptiveHash hasher = new PerceptiveHash(HASH_BIT_RESOLUTION);
        Hash uploadedHash = hasher.hash(uploadedImage);
        BigInteger uploadedValue = uploadedHash.getHashValue();
        int actualBitLength = uploadedHash.getBitResolution();

        List<CardImageHash> shortlistCards = buildPHashShortlist(allHashes, uploadedValue, actualBitLength);
        log.debug("pHash shortlist: {} candidates", shortlistCards.size());

        float[] uploadedEmbedding = null;
        if (cnnEmbeddingService.isAvailable()) {
            try {
                uploadedEmbedding = cnnEmbeddingService.extractArtEmbedding(uploadedImage);
            } catch (Exception e) {
                log.debug("CNN embedding extraction failed: {}", e.getMessage());
            }
        }

        CardImageHash bestCard = null;
        OrbValidationResult bestOrbResult = null;
        double bestConfidence = -1.0;

        Mat sourceArt = null;
        try {
            sourceArt = prepareSourceArt(uploadedImage);
            for (CardImageHash card : shortlistCards) {
                try {
                    byte[] referenceBytes = minioStorage.download(card.getMinioPath());
                    BufferedImage referenceImage = ImageIO.read(new ByteArrayInputStream(referenceBytes));
                    if (referenceImage == null) {
                        continue;
                    }

                    OrbValidationResult orbResult = computeOrbValidation(sourceArt, referenceImage);

                    double pHashDist = normalizedHammingDistance(
                            uploadedValue,
                            new BigInteger(card.getPHash(), 16),
                            actualBitLength);
                    double confidence = combineConfidence(pHashDist, orbResult.score());

                    if (uploadedEmbedding != null && card.getCnnEmbedding() != null) {
                        float[] refEmbedding = CnnEmbeddingService.base64ToEmbedding(card.getCnnEmbedding());
                        double cnnSim = CnnEmbeddingService.cosineSimilarity(uploadedEmbedding, refEmbedding);
                        double cnnBoost = Math.max(0.0, (cnnSim - 0.5) * 0.10);
                        confidence += cnnBoost;
                    }

                    if (confidence > bestConfidence) {
                        bestCard = card;
                        bestOrbResult = orbResult;
                        bestConfidence = confidence;
                    }
                } catch (Exception e) {
                    log.warn("Failed to validate candidate {}/{}: {}",
                            card.getSetCode(), card.getCollectorNumber(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to prepare source image for ORB: {}", e.getMessage());
        } finally {
            if (sourceArt != null) sourceArt.release();
        }

        if (bestCard == null || bestOrbResult == null) {
            return null;
        }

        boolean orbAccepted = bestOrbResult.inliers() >= MIN_ORB_INLIERS
                || bestOrbResult.goodMatches() >= MIN_ORB_GOOD_MATCHES;
        if (!orbAccepted && bestConfidence < MIN_HYBRID_CONFIDENCE) {
            return null;
        }

        double roundedConfidence = Math.round(bestConfidence * 100.0) / 100.0;
        return new MatchResult(bestCard, roundedConfidence);
    }

    private List<CardImageHash> buildPHashShortlist(List<CardImageHash> allHashes,
                                                    BigInteger uploadedValue, int actualBitLength) {
        List<Candidate> rankedCandidates = allHashes.stream()
                .map(h -> new Candidate(h,
                        normalizedHammingDistance(uploadedValue, new BigInteger(h.getPHash(), 16), actualBitLength)))
                .sorted(Comparator.comparingDouble(Candidate::pHashDistance))
                .toList();

        List<Candidate> shortlist = rankedCandidates.stream()
                .filter(c -> c.pHashDistance() <= PHASH_SHORTLIST_DISTANCE)
                .limit(PHASH_SHORTLIST_SIZE)
                .toList();
        if (shortlist.isEmpty()) {
            shortlist = rankedCandidates.stream().limit(PHASH_SHORTLIST_SIZE).toList();
        }
        return shortlist.stream().map(Candidate::card).toList();
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

                String hash = computeHash(image);
                CardImageHash entity = new CardImageHash();
                entity.setSetCode(parsed.setCode);
                entity.setCollectorNumber(parsed.collectorNumber);
                entity.setCardName(parsed.cardName);
                entity.setPHash(hash);
                entity.setMinioPath(objectKey);
                computeAndSetEmbedding(entity, image);
                hashRepository.save(entity);
                count++;

                log.info("Populated hash for {}/{} ({})", parsed.setCode, parsed.collectorNumber, parsed.cardName);
            } catch (Exception e) {
                log.warn("Failed to process MinIO object '{}': {}", objectKey, e.getMessage());
            }
        }

        log.info("Populated {} hashes from MinIO bucket", count);
        return count;
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
        if (!cnnEmbeddingService.isAvailable()) {
            return;
        }
        try {
            float[] embedding = cnnEmbeddingService.extractArtEmbedding(image);
            entity.setCnnEmbedding(CnnEmbeddingService.embeddingToBase64(embedding));
        } catch (Exception e) {
            log.warn("Failed to compute CNN embedding for {}/{}: {}",
                    entity.getSetCode(), entity.getCollectorNumber(), e.getMessage());
        }
    }

    private double normalizedHammingDistance(BigInteger a, BigInteger b, int bitLength) {
        BigInteger xor = a.xor(b);
        int diffBits = xor.bitCount();
        return (double) diffBits / bitLength;
    }

    private double combineConfidence(double pHashDistance, double orbScore) {
        double pHashScore = 1.0 - Math.min(1.0, pHashDistance);
        return (pHashScore * 0.35) + (orbScore * 0.65);
    }



    private Mat prepareSourceArt(BufferedImage sourceImage) throws IOException {
        Mat sourceFull = toNormalizedGrayMat(sourceImage);
        try {
            return extractArtRegion(sourceFull);
        } finally {
            sourceFull.release();
        }
    }

    private OrbValidationResult computeOrbValidation(Mat sourceArt,
                                                     BufferedImage referenceImage) throws IOException {
        Mat referenceFull = null;
        Mat reference = null;
        MatOfKeyPoint sourceKeypoints = new MatOfKeyPoint();
        MatOfKeyPoint referenceKeypoints = new MatOfKeyPoint();
        Mat sourceDescriptors = new Mat();
        Mat referenceDescriptors = new Mat();
        Mat inlierMask = new Mat();
        Mat noMask = new Mat();
        ORB orb = ORB.create(ORB_FEATURES);
        BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);

        try {
            referenceFull = toNormalizedGrayMat(referenceImage);
            reference = extractArtRegion(referenceFull);
            referenceFull.release();
            referenceFull = null;

            orb.detectAndCompute(sourceArt, noMask, sourceKeypoints, sourceDescriptors);
            orb.detectAndCompute(reference, noMask, referenceKeypoints, referenceDescriptors);
            if (sourceDescriptors.empty() || referenceDescriptors.empty()) {
                return new OrbValidationResult(0.0, 0, 0);
            }

            List<MatOfDMatch> knnMatches = new ArrayList<>();
            matcher.knnMatch(sourceDescriptors, referenceDescriptors, knnMatches, 2);
            List<DMatch> goodMatches = new ArrayList<>();
            for (MatOfDMatch matchGroup : knnMatches) {
                try {
                    DMatch[] matches = matchGroup.toArray();
                    if (matches.length < 2) {
                        continue;
                    }
                    if (matches[0].distance < ORB_RATIO_TEST * matches[1].distance) {
                        goodMatches.add(matches[0]);
                    }
                } finally {
                    matchGroup.release();
                }
            }

            if (goodMatches.isEmpty()) {
                return new OrbValidationResult(0.0, 0, 0);
            }

            int inliers = 0;
            if (goodMatches.size() >= 4) {
                List<org.opencv.core.Point> sourcePoints = new ArrayList<>();
                List<org.opencv.core.Point> referencePoints = new ArrayList<>();
                org.opencv.core.KeyPoint[] sourceKeyPointArray = sourceKeypoints.toArray();
                org.opencv.core.KeyPoint[] referenceKeyPointArray = referenceKeypoints.toArray();
                for (DMatch match : goodMatches) {
                    sourcePoints.add(sourceKeyPointArray[match.queryIdx].pt);
                    referencePoints.add(referenceKeyPointArray[match.trainIdx].pt);
                }
                MatOfPoint2f sourceMat = new MatOfPoint2f();
                sourceMat.fromList(sourcePoints);
                MatOfPoint2f referenceMat = new MatOfPoint2f();
                referenceMat.fromList(referencePoints);
                try {
                    Calib3d.findHomography(sourceMat, referenceMat, Calib3d.RANSAC, 5.0, inlierMask);
                    for (int i = 0; i < inlierMask.rows(); i++) {
                        double[] value = inlierMask.get(i, 0);
                        if (value != null && value.length > 0 && value[0] != 0.0) {
                            inliers++;
                        }
                    }
                } finally {
                    sourceMat.release();
                    referenceMat.release();
                }
            }

            double inlierScore = Math.min(1.0, inliers / 20.0);
            double matchScore = Math.min(1.0, goodMatches.size() / 30.0);
            double score = Math.max(inlierScore, matchScore * 0.75);
            return new OrbValidationResult(score, goodMatches.size(), inliers);
        } finally {
            if (referenceFull != null) referenceFull.release();
            if (reference != null) reference.release();
            sourceKeypoints.release();
            referenceKeypoints.release();
            sourceDescriptors.release();
            referenceDescriptors.release();
            inlierMask.release();
            noMask.release();
            matcher.clear();
            orb.clear();
        }
    }

    private Mat extractArtRegion(Mat gray) {
        int h = gray.rows();
        int w = gray.cols();
        int artTop = (int) (h * 0.08);
        int artBottom = (int) (h * 0.55);
        int artLeft = (int) (w * 0.05);
        int artRight = (int) (w * 0.95);
        Rect artRect = new Rect(artLeft, artTop, artRight - artLeft, artBottom - artTop);
        Mat sub = gray.submat(artRect);
        try {
            return sub.clone();
        } finally {
            sub.release();
        }
    }

    private Mat toNormalizedGrayMat(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        MatOfByte bytes = new MatOfByte(output.toByteArray());
        Mat decoded = Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR);
        Mat gray = new Mat();
        boolean success = false;
        try {
            Imgproc.cvtColor(decoded, gray, Imgproc.COLOR_BGR2GRAY);
            double scale = (double) ORB_TARGET_HEIGHT / gray.rows();
            if (Math.abs(scale - 1.0) > 0.05) {
                Mat resized = new Mat();
                Imgproc.resize(gray, resized, new Size(), scale, scale, Imgproc.INTER_AREA);
                gray.release();
                gray = resized;
            }
            org.opencv.imgproc.CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
            try {
                clahe.apply(gray, gray);
            } finally {
                clahe.collectGarbage();
            }
            success = true;
            return gray;
        } finally {
            decoded.release();
            bytes.release();
            if (!success) {
                gray.release();
            }
        }
    }
}
