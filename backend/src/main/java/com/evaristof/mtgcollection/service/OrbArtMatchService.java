package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import jakarta.annotation.PreDestroy;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches a photo's card art against the reference cards via a two-stage
 * retrieval that scales to tens of thousands of references:
 *
 * <ol>
 *   <li><b>Shortlist (Bag of Visual Words):</b> ORB descriptors are quantized
 *       against a learned visual vocabulary; each card (and the query) becomes
 *       a TF-IDF-weighted histogram of visual words. The shortlist is the
 *       top-K cards by cosine similarity. Because it's built on the ORB
 *       features that actually match, it's robust to foil glare / lighting —
 *       where a perceptual hash of the art fails.</li>
 *   <li><b>Verify (ORB + homography):</b> full feature matching + RANSAC
 *       inlier counting on those K candidates only, ranked by inliers.</li>
 * </ol>
 *
 * <p>Verification descriptors are held in a bounded LRU cache (built on demand
 * from each card's MinIO image). All OpenCV work runs under a single lock,
 * which — together with holding cached native Mats alive — avoids the
 * OpenCV-on-Windows finalizer race that used to corrupt native memory.
 *
 * <p>Phase 1: the vocabulary and per-card histograms are built in memory at
 * warm-up by re-extracting descriptors for every reference. This is fine for
 * the current scale; for the full ~90k Scryfall set the histograms will be
 * persisted so start-up doesn't re-extract everything.
 */
@Service
public class OrbArtMatchService {

    private static final Logger log = LoggerFactory.getLogger(OrbArtMatchService.class);

    private static final int ORB_FEATURES = 1500;
    private static final float ORB_RATIO_TEST = 0.75f;
    private static final int ORB_TARGET_HEIGHT = 700;
    private static final double ART_TOP = 0.08;
    private static final double ART_BOTTOM = 0.55;
    private static final double ART_LEFT = 0.05;
    private static final double ART_RIGHT = 0.95;

    // Bag-of-Visual-Words parameters.
    private static final int VOCAB_SIZE = 800;
    private static final int VOCAB_SAMPLE_PER_CARD = 200;
    private static final int SHORTLIST_K = 100;
    private static final int DESCRIPTOR_CACHE_MAX = 2000;

    private final CardImageHashRepository hashRepository;
    private final MinioStorageService minioStorage;
    @SuppressWarnings("unused")
    private final CardPerspectiveService perspectiveService;

    private final Object lock = new Object();
    private ORB orb;
    private BFMatcher verifyMatcher;   // NORM_HAMMING, binary ORB descriptors
    private BFMatcher wordMatcher;     // NORM_L2, float descriptors -> vocab word

    // BoVW model (rebuilt on invalidate()).
    private Mat vocabulary;            // VOCAB_SIZE x 32 (CV_32F)
    private float[] idf;               // length VOCAB_SIZE
    private List<CardVector> cardVectors;  // per-card L2-normalized tf-idf histogram
    private boolean modelReady;

    // Stage-2 bounded LRU of ORB (binary) descriptors, keyed by card id.
    private final LinkedHashMap<Long, RefEntry> descriptorCache =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, RefEntry> eldest) {
                    if (size() > DESCRIPTOR_CACHE_MAX) {
                        releaseEntry(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            };

    public record ScoredCard(CardImageHash card, int goodMatches, int inliers, double score) {}

    private record CardVector(CardImageHash card, float[] histogram) {}

    private static final class RefEntry {
        final MatOfKeyPoint keypoints;
        final KeyPoint[] keypointArray;
        final Mat descriptors;
        final boolean usable;

        RefEntry(MatOfKeyPoint keypoints, KeyPoint[] keypointArray, Mat descriptors, boolean usable) {
            this.keypoints = keypoints;
            this.keypointArray = keypointArray;
            this.descriptors = descriptors;
            this.usable = usable;
        }

        static RefEntry unusable() {
            return new RefEntry(null, null, null, false);
        }
    }

    public OrbArtMatchService(CardImageHashRepository hashRepository,
                              MinioStorageService minioStorage,
                              CardPerspectiveService perspectiveService) {
        this.hashRepository = hashRepository;
        this.minioStorage = minioStorage;
        this.perspectiveService = perspectiveService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmUpAsync() {
        try {
            rebuild();
        } catch (Exception e) {
            log.warn("BoVW warm-up failed (built lazily on first scan): {}", e.getMessage());
        }
    }

    /** Forces a full rebuild of the BoVW model from all reference images. */
    public void rebuild() {
        synchronized (lock) {
            ensureOrb();
            buildModel();
        }
    }

    /** Drops the model + descriptor cache so they rebuild on the next scan. */
    public void invalidate() {
        synchronized (lock) {
            releaseModel();
            releaseDescriptorCache();
        }
    }

    /**
     * Ranks reference cards for the given (already perspective-corrected) photo
     * via BoVW shortlist + ORB verify. Highest score first.
     */
    public List<ScoredCard> match(BufferedImage correctedPhoto) {
        synchronized (lock) {
            ensureModel();
            return matchLocked(correctedPhoto);
        }
    }

    private List<ScoredCard> matchLocked(BufferedImage correctedPhoto) {
        if (!modelReady || cardVectors.isEmpty()) {
            return List.of();
        }
        Mat art = null;
        MatOfKeyPoint queryKp = new MatOfKeyPoint();
        Mat queryDesc = new Mat();
        Mat noMask = new Mat();
        try {
            art = toArtMat(correctedPhoto);
            orb.detectAndCompute(art, noMask, queryKp, queryDesc);
            if (queryDesc.empty()) {
                return List.of();
            }
            float[] queryHist = histogramOf(queryDesc);
            if (queryHist == null) {
                return List.of();
            }
            KeyPoint[] queryKpArray = queryKp.toArray();

            List<CardVector> shortlist = shortlist(queryHist);

            List<ScoredCard> results = new ArrayList<>();
            for (CardVector cv : shortlist) {
                RefEntry ref = getOrBuildRef(cv.card());
                if (ref == null || !ref.usable) {
                    continue;
                }
                ScoredCard scored = scoreAgainst(cv.card(), queryDesc, queryKpArray, ref);
                if (scored != null) {
                    results.add(scored);
                }
            }
            results.sort(Comparator
                    .comparingInt(ScoredCard::inliers).reversed()
                    .thenComparing(Comparator.comparingInt(ScoredCard::goodMatches).reversed()));
            return results;
        } catch (Exception e) {
            log.warn("ORB art match failed: {}", e.getMessage());
            return List.of();
        } finally {
            if (art != null) art.release();
            queryKp.release();
            queryDesc.release();
            noMask.release();
        }
    }

    private List<CardVector> shortlist(float[] queryHist) {
        return cardVectors.stream()
                .sorted(Comparator.comparingDouble((CardVector cv) -> -cosine(queryHist, cv.histogram())))
                .limit(SHORTLIST_K)
                .toList();
    }

    /** BoVW shortlist rank (0-based) of a specific card for a photo, or -1.
     *  Diagnostic use. */
    public int bovwShortlistRank(BufferedImage correctedPhoto, String expectedSet, String expectedNumber) {
        synchronized (lock) {
            ensureModel();
            if (!modelReady) return -1;
            Mat art = null;
            MatOfKeyPoint kp = new MatOfKeyPoint();
            Mat desc = new Mat();
            Mat noMask = new Mat();
            try {
                art = toArtMat(correctedPhoto);
                orb.detectAndCompute(art, noMask, kp, desc);
                if (desc.empty()) return -1;
                float[] qh = histogramOf(desc);
                if (qh == null) return -1;
                List<CardVector> ranked = cardVectors.stream()
                        .sorted(Comparator.comparingDouble((CardVector cv) -> -cosine(qh, cv.histogram())))
                        .toList();
                for (int i = 0; i < ranked.size(); i++) {
                    CardImageHash c = ranked.get(i).card();
                    if (c.getSetCode().equalsIgnoreCase(expectedSet)
                            && c.getCollectorNumber().equalsIgnoreCase(expectedNumber)) {
                        return i;
                    }
                }
                return -1;
            } catch (Exception e) {
                return -1;
            } finally {
                if (art != null) art.release();
                kp.release();
                desc.release();
                noMask.release();
            }
        }
    }

    // ------------------------------------------------------------------
    // BoVW model build
    // ------------------------------------------------------------------

    private void ensureModel() {
        ensureOrb();
        if (!modelReady) {
            buildModel();
        }
    }

    /** Builds vocabulary + per-card histograms. Caller holds {@link #lock}. */
    private void buildModel() {
        releaseModel();
        List<CardImageHash> cards = hashRepository.findAll();
        // Pass 1: extract float descriptors per card (held temporarily).
        Map<Long, Mat> floatDescByCard = new LinkedHashMap<>();
        Map<Long, CardImageHash> cardById = new LinkedHashMap<>();
        List<Mat> vocabSample = new ArrayList<>();
        try {
            for (CardImageHash card : cards) {
                Mat fd = extractFloatDescriptors(card);
                if (fd == null || fd.rows() == 0) {
                    if (fd != null) fd.release();
                    continue;
                }
                floatDescByCard.put(card.getId(), fd);
                cardById.put(card.getId(), card);
                int take = Math.min(VOCAB_SAMPLE_PER_CARD, fd.rows());
                vocabSample.add(fd.rowRange(0, take));
            }
            if (floatDescByCard.isEmpty()) {
                cardVectors = List.of();
                modelReady = true;
                return;
            }

            // Train vocabulary via k-means on the sample.
            Mat sample = new Mat();
            Core.vconcat(vocabSample, sample);
            int k = Math.min(VOCAB_SIZE, sample.rows());
            Mat labels = new Mat();
            Mat centers = new Mat();
            try {
                TermCriteria crit = new TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 15, 1.0);
                Core.kmeans(sample, k, labels, crit, 1, Core.KMEANS_PP_CENTERS, centers);
                vocabulary = centers.clone();
            } finally {
                sample.release();
                labels.release();
                centers.release();
            }
            wordMatcher = BFMatcher.create(Core.NORM_L2, false);

            // Pass 2: raw histograms + document frequency.
            int vocab = vocabulary.rows();
            int[] df = new int[vocab];
            Map<Long, float[]> rawHist = new LinkedHashMap<>();
            for (Map.Entry<Long, Mat> e : floatDescByCard.entrySet()) {
                float[] h = assignWords(e.getValue(), vocab);
                rawHist.put(e.getKey(), h);
                for (int w = 0; w < vocab; w++) {
                    if (h[w] > 0) df[w]++;
                }
            }
            int n = rawHist.size();
            idf = new float[vocab];
            for (int w = 0; w < vocab; w++) {
                idf[w] = (float) Math.log((1.0 + n) / (1.0 + df[w])) + 1.0f;
            }

            // Apply tf-idf + L2 normalize.
            List<CardVector> vectors = new ArrayList<>(n);
            for (Map.Entry<Long, float[]> e : rawHist.entrySet()) {
                float[] h = e.getValue();
                for (int w = 0; w < vocab; w++) {
                    h[w] *= idf[w];
                }
                l2normalize(h);
                vectors.add(new CardVector(cardById.get(e.getKey()), h));
            }
            cardVectors = vectors;
            modelReady = true;
            log.info("BoVW model built: vocab={}, cards={}", vocab, vectors.size());
        } finally {
            for (Mat m : floatDescByCard.values()) {
                m.release();
            }
        }
    }

    /** Extracts ORB descriptors for a card's art and returns them as CV_32F. */
    private Mat extractFloatDescriptors(CardImageHash card) {
        Mat art = null;
        MatOfKeyPoint kp = new MatOfKeyPoint();
        Mat desc = new Mat();
        Mat noMask = new Mat();
        try {
            byte[] bytes = minioStorage.download(card.getMinioPath());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return null;
            art = toArtMat(image);
            orb.detectAndCompute(art, noMask, kp, desc);
            if (desc.empty()) return null;
            Mat f = new Mat();
            desc.convertTo(f, CvType.CV_32F);
            return f;
        } catch (Exception e) {
            log.debug("Could not extract descriptors for {}/{}: {}",
                    card.getSetCode(), card.getCollectorNumber(), e.getMessage());
            return null;
        } finally {
            if (art != null) art.release();
            kp.release();
            desc.release();
            noMask.release();
        }
    }

    /** Raw visual-word histogram for a set of float descriptors. */
    private float[] assignWords(Mat floatDesc, int vocab) {
        float[] hist = new float[vocab];
        MatOfDMatch matches = new MatOfDMatch();
        try {
            wordMatcher.match(floatDesc, vocabulary, matches);
            for (DMatch m : matches.toArray()) {
                if (m.trainIdx >= 0 && m.trainIdx < vocab) {
                    hist[m.trainIdx] += 1.0f;
                }
            }
        } finally {
            matches.release();
        }
        return hist;
    }

    /** Query histogram (binary descriptors → float → tf-idf → L2 norm). */
    private float[] histogramOf(Mat binaryDesc) {
        Mat f = new Mat();
        try {
            binaryDesc.convertTo(f, CvType.CV_32F);
            float[] h = assignWords(f, vocabulary.rows());
            for (int w = 0; w < h.length; w++) {
                h[w] *= idf[w];
            }
            l2normalize(h);
            return h;
        } catch (Exception e) {
            return null;
        } finally {
            f.release();
        }
    }

    private static void l2normalize(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        if (s <= 0) return;
        float inv = (float) (1.0 / Math.sqrt(s));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    private static double cosine(float[] a, float[] b) {
        // Both are L2-normalized, so cosine == dot product.
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot;
    }

    // ------------------------------------------------------------------
    // ORB verification (stage 2)
    // ------------------------------------------------------------------

    private RefEntry getOrBuildRef(CardImageHash card) {
        RefEntry ref = descriptorCache.get(card.getId());
        if (ref == null) {
            ref = buildEntry(card);
            descriptorCache.put(card.getId(), ref);
        }
        return ref;
    }

    private ScoredCard scoreAgainst(CardImageHash card, Mat queryDesc, KeyPoint[] queryKpArray, RefEntry ref) {
        List<MatOfDMatch> knnMatches = new ArrayList<>();
        try {
            verifyMatcher.knnMatch(queryDesc, ref.descriptors, knnMatches, 2);
        } catch (Exception e) {
            for (MatOfDMatch m : knnMatches) m.release();
            return null;
        }

        List<DMatch> goodMatches = new ArrayList<>();
        for (MatOfDMatch matchGroup : knnMatches) {
            try {
                DMatch[] matches = matchGroup.toArray();
                if (matches.length >= 2 && matches[0].distance < ORB_RATIO_TEST * matches[1].distance) {
                    goodMatches.add(matches[0]);
                }
            } finally {
                matchGroup.release();
            }
        }
        if (goodMatches.isEmpty()) {
            return new ScoredCard(card, 0, 0, 0.0);
        }

        int inliers = 0;
        if (goodMatches.size() >= 4) {
            inliers = countHomographyInliers(goodMatches, queryKpArray, ref.keypointArray);
        }
        double inlierScore = Math.min(1.0, inliers / 20.0);
        double matchScore = Math.min(1.0, goodMatches.size() / 30.0);
        double score = Math.max(inlierScore, matchScore * 0.75);
        return new ScoredCard(card, goodMatches.size(), inliers, score);
    }

    private int countHomographyInliers(List<DMatch> goodMatches, KeyPoint[] queryKp, KeyPoint[] refKp) {
        List<Point> queryPoints = new ArrayList<>();
        List<Point> refPoints = new ArrayList<>();
        for (DMatch match : goodMatches) {
            if (match.queryIdx < 0 || match.queryIdx >= queryKp.length
                    || match.trainIdx < 0 || match.trainIdx >= refKp.length) {
                continue;
            }
            queryPoints.add(queryKp[match.queryIdx].pt);
            refPoints.add(refKp[match.trainIdx].pt);
        }
        if (queryPoints.size() < 4) {
            return 0;
        }
        MatOfPoint2f queryMat = new MatOfPoint2f();
        MatOfPoint2f refMat = new MatOfPoint2f();
        Mat inlierMask = new Mat();
        try {
            queryMat.fromList(queryPoints);
            refMat.fromList(refPoints);
            Core.setRNGSeed(12345);
            Mat homography = Calib3d.findHomography(queryMat, refMat, Calib3d.RANSAC, 5.0, inlierMask);
            if (homography != null) {
                homography.release();
            }
            int inliers = 0;
            for (int i = 0; i < inlierMask.rows(); i++) {
                double[] v = inlierMask.get(i, 0);
                if (v != null && v.length > 0 && v[0] != 0.0) {
                    inliers++;
                }
            }
            return inliers;
        } finally {
            queryMat.release();
            refMat.release();
            inlierMask.release();
        }
    }

    private RefEntry buildEntry(CardImageHash card) {
        Mat art = null;
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        Mat noMask = new Mat();
        boolean keep = false;
        try {
            byte[] bytes = minioStorage.download(card.getMinioPath());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return RefEntry.unusable();
            }
            art = toArtMat(image);
            orb.detectAndCompute(art, noMask, keypoints, descriptors);
            if (descriptors.empty()) {
                return RefEntry.unusable();
            }
            keep = true;
            return new RefEntry(keypoints, keypoints.toArray(), descriptors, true);
        } catch (Exception e) {
            log.debug("Could not cache ORB features for {}/{}: {}",
                    card.getSetCode(), card.getCollectorNumber(), e.getMessage());
            return RefEntry.unusable();
        } finally {
            if (art != null) art.release();
            noMask.release();
            if (!keep) {
                keypoints.release();
                descriptors.release();
            }
        }
    }

    private void ensureOrb() {
        if (orb == null) {
            orb = ORB.create(ORB_FEATURES);
            verifyMatcher = BFMatcher.create(Core.NORM_HAMMING, false);
        }
    }

    private Mat toArtMat(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        MatOfByte bytes = new MatOfByte(output.toByteArray());
        Mat decoded = Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR);
        Mat gray = new Mat();
        Mat art = null;
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
            int h = gray.rows();
            int w = gray.cols();
            Rect artRect = new Rect(
                    (int) (w * ART_LEFT), (int) (h * ART_TOP),
                    (int) (w * (ART_RIGHT - ART_LEFT)), (int) (h * (ART_BOTTOM - ART_TOP)));
            Mat sub = gray.submat(artRect);
            try {
                art = sub.clone();
            } finally {
                sub.release();
            }
            success = true;
            return art;
        } finally {
            decoded.release();
            bytes.release();
            gray.release();
            if (!success && art != null) {
                art.release();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            releaseModel();
            releaseDescriptorCache();
            if (orb != null) orb.clear();
            if (verifyMatcher != null) verifyMatcher.clear();
            if (wordMatcher != null) wordMatcher.clear();
        }
    }

    private void releaseModel() {
        if (vocabulary != null) {
            vocabulary.release();
            vocabulary = null;
        }
        idf = null;
        cardVectors = null;
        modelReady = false;
    }

    private void releaseDescriptorCache() {
        for (RefEntry entry : descriptorCache.values()) {
            releaseEntry(entry);
        }
        descriptorCache.clear();
    }

    private void releaseEntry(RefEntry entry) {
        if (entry != null && entry.usable) {
            entry.keypoints.release();
            entry.descriptors.release();
        }
    }
}
