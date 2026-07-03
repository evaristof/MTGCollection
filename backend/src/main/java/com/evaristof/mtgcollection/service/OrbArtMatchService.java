package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import jakarta.annotation.PreDestroy;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matches a photo's card art against every reference card by ORB features.
 *
 * <p>Rather than pre-filtering with a perceptual-hash shortlist (which drops
 * the correct card for real phone photos — foil glare, lighting and borders
 * shift the whole-card hash enough to rank the right card outside the top-N),
 * this evaluates the art-region ORB match against ALL reference cards. ORB on
 * the artwork is a near-perfect, language- and frame-independent discriminator
 * (the art is identical across a card's English/Portuguese/foil printings).
 *
 * <p>To make "match against everything" scale, each reference's ORB keypoints
 * and descriptors are computed once and cached in memory (keyed by card id).
 * A scan then only computes the query image's descriptors once and runs the
 * cheap {@code knnMatch} against each cached reference. Holding the cached
 * native Mats alive also sidesteps the OpenCV-on-Windows failure mode where
 * the GC finalizer thread freed per-scan native objects mid-scan and corrupted
 * the heap — nothing here is left for the finalizer, and all OpenCV work runs
 * under a single lock.
 */
@Service
public class OrbArtMatchService {

    private static final Logger log = LoggerFactory.getLogger(OrbArtMatchService.class);

    private static final int ORB_FEATURES = 1500;
    private static final float ORB_RATIO_TEST = 0.75f;
    private static final int ORB_TARGET_HEIGHT = 700;
    // Art window as a fraction of the (perspective-corrected) card: the
    // illustration box, excluding title bar, type line and text box.
    private static final double ART_TOP = 0.08;
    private static final double ART_BOTTOM = 0.55;
    private static final double ART_LEFT = 0.05;
    private static final double ART_RIGHT = 0.95;

    private final CardImageHashRepository hashRepository;
    private final MinioStorageService minioStorage;
    // CardPerspectiveService is injected only to guarantee its static
    // initializer (the single OpenCV.loadLocally() + setNumThreads(1) for the
    // whole app) has run before this service touches any OpenCV type.
    @SuppressWarnings("unused")
    private final CardPerspectiveService perspectiveService;

    private final Object lock = new Object();
    private final Map<Long, RefEntry> cache = new ConcurrentHashMap<>();
    private ORB orb;
    private BFMatcher matcher;

    /** A ranked ORB match against one reference card. */
    public record ScoredCard(CardImageHash card, int goodMatches, int inliers, double score) {}

    /** Cached ORB features for one reference card. Native Mats held alive. */
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
    public void warmUpCacheAsync() {
        try {
            int n = ensureCache();
            log.info("ORB descriptor cache warmed up: {} reference cards", n);
        } catch (Exception e) {
            log.warn("ORB cache warm-up failed (will build lazily on first scan): {}", e.getMessage());
        }
    }

    /**
     * Ranks all reference cards by how well their cached art descriptors match
     * the given (already perspective-corrected) photo. Highest score first.
     * Never throws — returns an empty list if the query yields no features.
     */
    public List<ScoredCard> match(BufferedImage correctedPhoto) {
        synchronized (lock) {
            ensureOrb();
            ensureCache();
            return matchLocked(correctedPhoto);
        }
    }

    private List<ScoredCard> matchLocked(BufferedImage correctedPhoto) {
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
            KeyPoint[] queryKpArray = queryKp.toArray();

            List<ScoredCard> results = new ArrayList<>();
            List<CardImageHash> all = hashRepository.findAll();
            for (CardImageHash card : all) {
                RefEntry ref = cache.get(card.getId());
                if (ref == null || !ref.usable) {
                    continue;
                }
                ScoredCard scored = scoreAgainst(card, queryDesc, queryKpArray, ref);
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

    private ScoredCard scoreAgainst(CardImageHash card, Mat queryDesc, KeyPoint[] queryKpArray, RefEntry ref) {
        List<MatOfDMatch> knnMatches = new ArrayList<>();
        try {
            matcher.knnMatch(queryDesc, ref.descriptors, knnMatches, 2);
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
            // Fixed RNG seed keeps RANSAC's inlier count reproducible across
            // identical scans.
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

    /**
     * Builds cache entries for any reference cards not yet cached. Returns the
     * number of usable entries. Callers hold {@link #lock}.
     */
    private int ensureCache() {
        ensureOrb();
        int usable = 0;
        for (CardImageHash card : hashRepository.findAll()) {
            RefEntry entry = cache.get(card.getId());
            if (entry == null) {
                entry = buildEntry(card);
                cache.put(card.getId(), entry);
            }
            if (entry.usable) {
                usable++;
            }
        }
        return usable;
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

    /** Drops the cache so it is rebuilt on the next scan (e.g. after populate). */
    public void invalidate() {
        synchronized (lock) {
            releaseCache();
        }
    }

    private void ensureOrb() {
        if (orb == null) {
            orb = ORB.create(ORB_FEATURES);
            matcher = BFMatcher.create(Core.NORM_HAMMING, false);
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
            releaseCache();
            if (orb != null) {
                orb.clear();
            }
            if (matcher != null) {
                matcher.clear();
            }
        }
    }

    private void releaseCache() {
        for (RefEntry entry : cache.values()) {
            if (entry.usable) {
                entry.keypoints.release();
                entry.descriptors.release();
            }
        }
        cache.clear();
    }
}
