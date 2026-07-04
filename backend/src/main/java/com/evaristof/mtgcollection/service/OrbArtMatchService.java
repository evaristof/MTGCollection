package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.ScannerModel;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.ScannerModelRepository;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int SHORTLIST_K = 100;
    private static final int DESCRIPTOR_CACHE_MAX = 2000;
    // Vocabulary is trained on a bounded, spread-out sample: up to
    // VOCAB_TRAIN_CARDS cards (strided across the whole set for diversity),
    // VOCAB_SAMPLE_PER_CARD descriptors each (~75k rows total — the range the
    // proven 304-card model used; more just slows k-means without helping).
    private static final int VOCAB_TRAIN_CARDS = 1500;
    private static final int VOCAB_SAMPLE_PER_CARD = 50;
    // Above this many references, DON'T auto-build at startup: it's a long job
    // that holds the OpenCV lock and would hang every scan. Require the explicit
    // "Reconstruir Modelo" button (a background job with progress) instead.
    private static final int AUTO_BUILD_MAX = 2000;
    // How often (in cards) the long build reports progress.
    private static final int BUILD_PROGRESS_EVERY = 200;
    // Parallelism for the histogram pass. Each worker gets its OWN ORB + word
    // matcher (the vocabulary Mat is shared read-only), so OpenCV never shares
    // mutable state across threads. The work is CPU-bound (decode + OpenCV), so
    // ~cores is the sweet spot; override via scanner.model.build-threads to
    // experiment. Also raise spring.datasource.hikari.maximum-pool-size to match
    // so the concurrent per-card saves don't queue on the connection pool.
    @org.springframework.beans.factory.annotation.Value("${scanner.model.build-threads:0}")
    private int configuredBuildThreads;

    private final CardImageHashRepository hashRepository;
    private final ScannerModelRepository scannerModelRepository;
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
                              ScannerModelRepository scannerModelRepository,
                              MinioStorageService minioStorage,
                              CardPerspectiveService perspectiveService) {
        this.hashRepository = hashRepository;
        this.scannerModelRepository = scannerModelRepository;
        this.minioStorage = minioStorage;
        this.perspectiveService = perspectiveService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmUpAsync() {
        try {
            synchronized (lock) {
                ensureOrb();
                loadPersistedModel();
            }
            // Nothing persisted yet? Auto-build ONLY for small reference sets.
            // For a large set (the full Scryfall) the build is a long job that
            // would hold the lock and hang scans — the user triggers it via the
            // "Reconstruir Modelo" button (a background job with progress).
            if (!modelReady) {
                long n = hashRepository.count();
                if (n > 0 && n <= AUTO_BUILD_MAX) {
                    log.info("No persisted scanner model — building it once ({} cards)…", n);
                    rebuild();
                } else if (n > AUTO_BUILD_MAX) {
                    log.warn("No persisted scanner model and {} reference cards — skipping "
                            + "auto-build to avoid blocking scans. Use \"Reconstruir Modelo\".", n);
                }
            }
        } catch (Exception e) {
            log.warn("BoVW warm-up failed (built lazily on first scan): {}", e.getMessage());
        }
    }

    /**
     * Trains the vocabulary + per-card histograms from every reference image,
     * PERSISTS them (vocabulary row + each card's histogram column), and builds
     * the in-memory model. Expensive — run once after a bulk change; normal
     * startups just {@link #loadPersistedModel()}.
     */
    public void rebuild() {
        rebuild((phase, done, total) -> { });
    }

    public void rebuild(BuildProgress progress) {
        synchronized (lock) {
            ensureOrb();
            buildAndPersistModel(progress);
        }
    }

    /**
     * Resumable / incremental model update. If no vocabulary is persisted yet,
     * does a full {@link #buildAndPersistModel} (which trains the vocabulary).
     * Otherwise it reuses the persisted vocabulary and computes histograms ONLY
     * for the cards that don't have one yet — a resumed/interrupted build, or a
     * freshly downloaded edition — then loads the full in-memory model. Fast
     * when little is missing (instant when nothing is).
     */
    public void updateModel(BuildProgress progress) {
        synchronized (lock) {
            ensureOrb();
            Optional<ScannerModel> saved = scannerModelRepository.findById(ScannerModel.SINGLETON_ID);
            if (saved.isEmpty()) {
                buildAndPersistModel(progress); // no vocabulary yet → full build
                return;
            }
            // Reuse the persisted vocabulary; fill only the missing histograms.
            releaseModel();
            vocabulary = deserializeVocabulary(saved.get());
            wordMatcher = BFMatcher.create(Core.NORM_L2, false);
            List<CardImageHash> missing = hashRepository.findByBovwHistogramIsNull();
            log.info("Incremental model update: {} cards missing histograms", missing.size());
            if (!missing.isEmpty()) {
                buildHistogramsParallel(missing, vocabulary.rows(), progress);
            }
            // Build the in-memory model from ALL persisted histograms (fast).
            progress.update("Carregando modelo", 0, 0);
            loadPersistedModel();
        }
    }

    /** Progress callback for the (long) model build. */
    @FunctionalInterface
    public interface BuildProgress {
        void update(String phase, int done, int total);
    }

    /**
     * Computes and returns the sparse histogram string for one card image using
     * the current vocabulary, or {@code null} if no model is loaded yet. Lets a
     * newly-registered card become searchable without a full rebuild.
     */
    public String computeHistogramString(BufferedImage fullCard) {
        synchronized (lock) {
            if (!modelReady || vocabulary == null) {
                return null;
            }
            Mat art = null;
            MatOfKeyPoint kp = new MatOfKeyPoint();
            Mat desc = new Mat();
            Mat noMask = new Mat();
            Mat f = new Mat();
            try {
                art = toArtMat(fullCard);
                orb.detectAndCompute(art, noMask, kp, desc);
                if (desc.empty()) return null;
                desc.convertTo(f, CvType.CV_32F);
                float[] hist = assignWords(f, vocabulary.rows());
                return serializeHistogram(hist);
            } catch (Exception e) {
                return null;
            } finally {
                if (art != null) art.release();
                kp.release();
                desc.release();
                noMask.release();
                f.release();
            }
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
        // Compute similarity ONCE per card (the old comparator recomputed the
        // cosine on every comparison — O(n log n) cosines, painful at ~100k),
        // then take the top-K.
        List<ScoredVector> scored = new ArrayList<>(cardVectors.size());
        for (CardVector cv : cardVectors) {
            scored.add(new ScoredVector(cv, cosine(queryHist, cv.histogram())));
        }
        scored.sort(Comparator.comparingDouble((ScoredVector sv) -> sv.score()).reversed());
        List<CardVector> out = new ArrayList<>(Math.min(SHORTLIST_K, scored.size()));
        for (int i = 0; i < scored.size() && i < SHORTLIST_K; i++) {
            out.add(scored.get(i).cv());
        }
        return out;
    }

    private record ScoredVector(CardVector cv, double score) {}

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
            loadPersistedModel();
        }
    }

    /**
     * Loads the persisted vocabulary + per-card histograms from the DB and
     * builds the in-memory model. Fast (no image extraction). Leaves the model
     * "not ready" if nothing has been persisted yet. Caller holds the lock.
     */
    private void loadPersistedModel() {
        releaseModel();
        Optional<ScannerModel> saved = scannerModelRepository.findById(ScannerModel.SINGLETON_ID);
        if (saved.isEmpty()) {
            return;
        }
        ScannerModel model = saved.get();
        vocabulary = deserializeVocabulary(model);
        wordMatcher = BFMatcher.create(Core.NORM_L2, false);

        Map<CardImageHash, float[]> rawByCard = new LinkedHashMap<>();
        for (CardImageHash card : hashRepository.findAll()) {
            float[] raw = parseHistogram(card.getBovwHistogram(), vocabulary.rows());
            if (raw != null) {
                rawByCard.put(card, raw);
            }
        }
        buildVectorsFromRaw(rawByCard);
        modelReady = true;
        log.info("BoVW model loaded: vocab={}, cards={}", vocabulary.rows(), cardVectors.size());
    }

    /**
     * Trains the vocabulary from a representative SAMPLE of cards, then streams
     * every reference to compute + persist its histogram — releasing each
     * descriptor Mat immediately so memory stays bounded (the old version held
     * all ~100k descriptor Mats at once and OOM'd). Reports progress. Caller
     * holds the lock.
     */
    private void buildAndPersistModel(BuildProgress progress) {
        releaseModel();
        List<CardImageHash> cards = hashRepository.findAll();
        if (cards.isEmpty()) {
            cardVectors = List.of();
            modelReady = true;
            return;
        }
        int total = cards.size();

        // ---- Pass 1: train the vocabulary from a bounded, spread-out sample ----
        // Extraction is parallelized (same per-thread ORB pattern as Pass 2) and
        // reports progress, so it no longer sits silently for minutes.
        List<Mat> vocabSample = collectVocabSample(cards, progress);
        try {
            if (vocabSample.isEmpty()) {
                cardVectors = List.of();
                modelReady = true;
                return;
            }
            Mat sample = new Mat();
            Core.vconcat(vocabSample, sample);
            int k = Math.min(VOCAB_SIZE, sample.rows());
            Mat labels = new Mat();
            Mat centers = new Mat();
            try {
                // k-means is a single blocking call with no sub-progress — flip
                // the message so the UI doesn't look frozen during it.
                progress.update("Treinando vocabulário (k-means)", 0, total);
                TermCriteria crit = new TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 15, 1.0);
                Core.kmeans(sample, k, labels, crit, 1, Core.KMEANS_PP_CENTERS, centers);
                vocabulary = centers.clone();
            } finally {
                sample.release();
                labels.release();
                centers.release();
            }
        } finally {
            for (Mat m : vocabSample) {
                m.release();
            }
        }
        wordMatcher = BFMatcher.create(Core.NORM_L2, false);
        persistVocabulary();

        // ---- Pass 2: per-card histogram, in parallel (release each Mat) ----
        int vocab = vocabulary.rows();
        Map<CardImageHash, float[]> rawByCard = buildHistogramsParallel(cards, vocab, progress);

        progress.update("Finalizando", total, total);
        buildVectorsFromRaw(rawByCard);
        modelReady = true;
        log.info("BoVW model built + persisted: vocab={}, cards={}", vocab, cardVectors.size());
    }

    private int modelBuildThreads() {
        if (configuredBuildThreads > 0) {
            return configuredBuildThreads;
        }
        return Math.max(2, Math.min(32, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Extracts a bounded, spread-out sample of descriptors for k-means, in
     * parallel (one ORB per worker). Returns cloned sample-row Mats (caller
     * releases them). Reports progress.
     */
    private List<Mat> collectVocabSample(List<CardImageHash> cards, BuildProgress progress) {
        int total = cards.size();
        int stride = Math.max(1, total / VOCAB_TRAIN_CARDS);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < total; i += stride) {
            indices.add(i);
        }
        int sampleCount = indices.size();
        int threads = modelBuildThreads();
        log.info("Training vocabulary from {} sampled cards on {} threads", sampleCount, threads);
        progress.update("Treinando vocabulário", 0, sampleCount);
        List<Mat> vocabSample = java.util.Collections.synchronizedList(new ArrayList<>(sampleCount));
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicInteger done = new AtomicInteger(0);
        List<Thread> workers = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                ORB localOrb = ORB.create(ORB_FEATURES);
                int j;
                while ((j = cursor.getAndIncrement()) < sampleCount) {
                    Mat fd = extractFloatDescriptors(localOrb, cards.get(indices.get(j)));
                    if (fd != null && fd.rows() > 0) {
                        int take = Math.min(VOCAB_SAMPLE_PER_CARD, fd.rows());
                        vocabSample.add(fd.rowRange(0, take).clone());
                    }
                    if (fd != null) {
                        fd.release();
                    }
                    int d = done.incrementAndGet();
                    if (d % 100 == 0) {
                        progress.update("Treinando vocabulário", d, sampleCount);
                    }
                }
            }, "vocab-sample-" + t);
            workers.add(worker);
            worker.start();
        }
        for (Thread w : workers) {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return vocabSample;
    }

    /**
     * Computes + persists each card's BoVW histogram across a pool of worker
     * threads ({@link #modelBuildThreads()}). Each worker owns its own ORB and
     * word matcher; the vocabulary is shared read-only and every Mat is
     * thread-local + released, so no OpenCV state is shared mutably across
     * threads. The caller holds the service lock, so scans wait (they never run
     * OpenCV concurrently with this) — only the workers do, among themselves.
     */
    private Map<CardImageHash, float[]> buildHistogramsParallel(
            List<CardImageHash> cards, int vocab, BuildProgress progress) {
        int total = cards.size();
        int threads = modelBuildThreads();
        log.info("Building histograms for {} cards on {} threads", total, threads);
        Map<CardImageHash, float[]> rawByCard = new ConcurrentHashMap<>(Math.max(16, total));
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicInteger done = new AtomicInteger(0);
        List<Thread> workers = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                ORB localOrb = ORB.create(ORB_FEATURES);
                BFMatcher localMatcher = BFMatcher.create(Core.NORM_L2, false);
                int i;
                while ((i = cursor.getAndIncrement()) < total) {
                    CardImageHash card = cards.get(i);
                    Mat fd = extractFloatDescriptors(localOrb, card);
                    if (fd != null && fd.rows() > 0) {
                        try {
                            float[] h = assignWords(localMatcher, fd, vocab);
                            card.setBovwHistogram(serializeHistogram(h));
                            hashRepository.save(card);
                            rawByCard.put(card, h);
                        } catch (Exception e) {
                            log.debug("Histogram failed for {}/{}: {}",
                                    card.getSetCode(), card.getCollectorNumber(), e.getMessage());
                        } finally {
                            fd.release();
                        }
                    } else if (fd != null) {
                        fd.release();
                    }
                    int d = done.incrementAndGet();
                    if (d % BUILD_PROGRESS_EVERY == 0) {
                        progress.update("Gerando histogramas", d, total);
                    }
                }
            }, "model-build-" + t);
            workers.add(worker);
            worker.start();
        }
        for (Thread w : workers) {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rawByCard;
    }

    /** Computes idf from the raw histograms and builds the tf-idf, L2-normalized
     *  per-card vectors. Caller holds the lock. */
    private void buildVectorsFromRaw(Map<CardImageHash, float[]> rawByCard) {
        int vocab = vocabulary.rows();
        int[] df = new int[vocab];
        for (float[] h : rawByCard.values()) {
            for (int w = 0; w < vocab; w++) {
                if (h[w] > 0) df[w]++;
            }
        }
        int n = rawByCard.size();
        idf = new float[vocab];
        for (int w = 0; w < vocab; w++) {
            idf[w] = (float) Math.log((1.0 + n) / (1.0 + df[w])) + 1.0f;
        }
        List<CardVector> vectors = new ArrayList<>(n);
        for (Map.Entry<CardImageHash, float[]> e : rawByCard.entrySet()) {
            float[] h = e.getValue().clone();
            for (int w = 0; w < vocab; w++) {
                h[w] *= idf[w];
            }
            l2normalize(h);
            vectors.add(new CardVector(e.getKey(), h));
        }
        cardVectors = vectors;
    }

    // ------------------------------------------------------------------
    // Persistence (vocabulary + histogram serialization)
    // ------------------------------------------------------------------

    private void persistVocabulary() {
        int rows = vocabulary.rows();
        int cols = vocabulary.cols();
        float[] data = new float[rows * cols];
        vocabulary.get(0, 0, data);
        ByteBuffer buf = ByteBuffer.allocate(data.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : data) buf.putFloat(v);

        ScannerModel model = scannerModelRepository.findById(ScannerModel.SINGLETON_ID)
                .orElseGet(ScannerModel::new);
        model.setId(ScannerModel.SINGLETON_ID);
        model.setVocabSize(rows);
        model.setVocabCols(cols);
        model.setVocabulary(buf.array());
        scannerModelRepository.save(model);
    }

    private Mat deserializeVocabulary(ScannerModel model) {
        int rows = model.getVocabSize();
        int cols = model.getVocabCols();
        ByteBuffer buf = ByteBuffer.wrap(model.getVocabulary()).order(ByteOrder.LITTLE_ENDIAN);
        float[] data = new float[rows * cols];
        for (int i = 0; i < data.length; i++) data[i] = buf.getFloat();
        Mat m = new Mat(rows, cols, CvType.CV_32F);
        m.put(0, 0, data);
        return m;
    }

    /** Sparse "word:count,…" of the nonzero visual-word counts. */
    private String serializeHistogram(float[] rawCounts) {
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < rawCounts.length; w++) {
            int c = (int) rawCounts[w];
            if (c > 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append(w).append(':').append(c);
            }
        }
        return sb.toString();
    }

    private float[] parseHistogram(String s, int vocab) {
        if (s == null || s.isBlank()) {
            return null;
        }
        float[] h = new float[vocab];
        for (String pair : s.split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) continue;
            try {
                int w = Integer.parseInt(pair.substring(0, colon));
                int c = Integer.parseInt(pair.substring(colon + 1));
                if (w >= 0 && w < vocab) h[w] = c;
            } catch (NumberFormatException ignored) {
                // skip malformed pair
            }
        }
        return h;
    }

    /** Extracts ORB descriptors for a card's art and returns them as CV_32F. */
    private Mat extractFloatDescriptors(CardImageHash card) {
        return extractFloatDescriptors(orb, card);
    }

    /**
     * Same, but using a caller-supplied ORB instance so the parallel build can
     * run one ORB per worker thread (a single ORB is not thread-safe). All Mats
     * are thread-local and released here.
     */
    private Mat extractFloatDescriptors(ORB orbInstance, CardImageHash card) {
        Mat art = null;
        MatOfKeyPoint kp = new MatOfKeyPoint();
        Mat desc = new Mat();
        Mat noMask = new Mat();
        try {
            byte[] bytes = minioStorage.download(card.getMinioPath());
            art = toArtMatFromBytes(bytes);
            if (art == null) return null;
            orbInstance.detectAndCompute(art, noMask, kp, desc);
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
        return assignWords(wordMatcher, floatDesc, vocab);
    }

    /**
     * Same, but using a caller-supplied matcher so the parallel build can run
     * one matcher per worker thread. The {@code vocabulary} Mat is read-only
     * here, so it is safe to share across the workers.
     */
    private float[] assignWords(BFMatcher matcher, Mat floatDesc, int vocab) {
        float[] hist = new float[vocab];
        MatOfDMatch matches = new MatOfDMatch();
        try {
            matcher.match(floatDesc, vocabulary, matches);
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
            art = toArtMatFromBytes(bytes);
            if (art == null) {
                return RefEntry.unusable();
            }
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

    /**
     * Query path: the uploaded photo is already a decoded BufferedImage, so we
     * round-trip it through PNG for OpenCV to decode. Fine for a single scan.
     */
    private Mat toArtMat(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return toArtMatFromBytes(output.toByteArray());
    }

    /**
     * Reference path: builds the ORB art Mat directly from raw image bytes,
     * decoding ONCE with OpenCV. Reference extraction already has the PNG bytes
     * from MinIO, so this avoids the wasteful ImageIO-decode + PNG-re-encode +
     * re-decode round-trip that dominated the model build. Returns null if the
     * bytes can't be decoded.
     */
    private Mat toArtMatFromBytes(byte[] imageBytes) {
        MatOfByte buf = new MatOfByte(imageBytes);
        Mat decoded = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR);
        Mat gray = new Mat();
        Mat art = null;
        boolean success = false;
        try {
            if (decoded.empty()) {
                return null;
            }
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
            buf.release();
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
