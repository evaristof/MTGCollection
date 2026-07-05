package com.evaristof.mtgcollection.service;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a photo that contains MULTIPLE cards (e.g. a binder page) into one
 * rough crop per card. Each crop is a small image with a single card, which is
 * then fed to the existing single-card pipeline
 * ({@link CardPerspectiveService} → {@link CardImageMatchService#findBestMatch})
 * — this class does NOT try to recognise anything, only to locate + crop.
 *
 * <p>Detection is the same "document scanner" recipe as {@link CardPerspectiveService}
 * (bilateral blur → Canny → contours → minAreaRect), but it keeps EVERY
 * card-shaped rectangle (not just the largest), with a lower area floor since a
 * card is a small fraction of a multi-card frame, then suppresses overlapping
 * duplicates. Falls open: on any failure returns the whole image as a single
 * crop, so bulk scan degrades to normal scan.
 */
@Service
public class CardSplitterService {

    private static final Logger log = LoggerFactory.getLogger(CardSplitterService.class);

    // Physical MTG card ratio (63mm x 88mm).
    private static final double CARD_ASPECT_RATIO = 63.0 / 88.0;

    private static final int SEARCH_MAX_DIM = 1600;
    // A card in a grid of N is a small slice of the frame; keep the floor low
    // but above noise. The ceiling drops the whole-page / multi-row rectangles.
    private static final double MIN_AREA_FRACTION = 0.03;
    private static final double MAX_AREA_FRACTION = 0.60;
    // Overlap suppression: if two boxes overlap by more than this fraction of the
    // SMALLER box's area, they're the same card (nested border/sleeve) OR one is
    // a merged super-box swallowing the other. Using "fraction of smaller"
    // (IoMin) rather than IoU lets a big 2x2 merge be dropped in favour of the
    // real single cards it contains, which IoU misses (sizes too different).
    private static final double OVERLAP_MIN_FRACTION = 0.55;
    // Grow each crop so the perspective step still sees the full border. Erring
    // large is safe: perspective correction re-finds the card inside the crop,
    // so a little neighbour bleed is fine but a clipped title is not.
    private static final double CROP_PADDING_FRACTION = 0.06;
    // Morphological CLOSE kernel (px at SEARCH_MAX_DIM scale): bridges border
    // gaps where glare/foil breaks a card outline, without merging neighbours.
    private static final int SEP_KERNEL = 9;

    // Offline calibration hook: if set, receives the binary mask used for
    // detection (a clone the caller must release). Null in production.
    private java.util.function.Consumer<Mat> debugMaskSink;

    public void setDebugMaskSink(java.util.function.Consumer<Mat> sink) {
        this.debugMaskSink = sink;
    }

    // Injected only to guarantee OpenCV's native lib is loaded (by
    // CardPerspectiveService's static initializer) before this class runs.
    @SuppressWarnings("unused")
    private final CardPerspectiveService perspectiveService;

    public CardSplitterService(CardPerspectiveService perspectiveService) {
        this.perspectiveService = perspectiveService;
    }

    /**
     * Returns one crop per detected card, largest first. If nothing card-shaped
     * is found, returns a single element: the original image (so callers can
     * always fall back to normal single-card scanning).
     */
    public List<BufferedImage> splitCards(BufferedImage source) {
        Mat sourceMat = null;
        try {
            sourceMat = bufferedImageToMat(source);
            List<Rect> boxes = detectCardBoxes(sourceMat);
            if (boxes.isEmpty()) {
                log.info("Bulk split found no cards; returning the whole image");
                return List.of(source);
            }
            List<BufferedImage> crops = new ArrayList<>(boxes.size());
            for (Rect box : boxes) {
                Mat sub = new Mat(sourceMat, box);
                Mat upright = null;
                try {
                    // A card is portrait; if the crop came out landscape (the
                    // photo was rotated via EXIF, which ImageIO ignores), rotate
                    // it so the hover preview shows the card upright. Doesn't
                    // affect matching (perspective correction re-orients anyway).
                    if (sub.cols() > sub.rows()) {
                        upright = new Mat();
                        Core.rotate(sub, upright, Core.ROTATE_90_CLOCKWISE);
                        crops.add(matToBufferedImage(upright));
                    } else {
                        crops.add(matToBufferedImage(sub));
                    }
                } finally {
                    sub.release();
                    if (upright != null) upright.release();
                }
            }
            log.info("Bulk split detected {} card(s)", crops.size());
            return crops;
        } catch (Exception e) {
            log.warn("Bulk split failed, returning the whole image: {}", e.getMessage());
            return List.of(source);
        } finally {
            if (sourceMat != null) {
                sourceMat.release();
            }
        }
    }

    private List<Rect> detectCardBoxes(Mat sourceBgr) {
        double scale = Math.min(1.0,
                (double) SEARCH_MAX_DIM / Math.max(sourceBgr.rows(), sourceBgr.cols()));
        Mat small = new Mat();
        Mat gray = new Mat();
        Mat equalized = new Mat();
        Mat blurred = new Mat();
        Mat scratch = new Mat();
        Mat mask = new Mat();
        Mat opened = new Mat();
        Mat hierarchy = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(SEP_KERNEL, SEP_KERNEL));
        org.opencv.imgproc.CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.resize(sourceBgr, small, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY);
            // Normalise local contrast (CLAHE) so the SAME edge detector works
            // whether the cards are light-on-light (white cards, white binder) or
            // dark-on-dark (black cards, black binder + glare) — the two extremes
            // in the sample set. Without this, fixed Canny thresholds miss one end.
            clahe.apply(gray, equalized);
            Imgproc.bilateralFilter(equalized, blurred, 9, 75, 75);
            // Cards here often have dark frames as dark as the binder pocket, so a
            // brightness threshold can't isolate them. Their EDGES are strong
            // though: Canny the card outlines, then CLOSE (dilate→erode) to bridge
            // gaps where glare/foil breaks the border so each outline becomes one
            // closed loop. Canny thresholds come from each photo's own Otsu level
            // (auto-Canny) instead of fixed constants, adapting to its contrast.
            double otsu = Imgproc.threshold(blurred, scratch, 0, 255,
                    Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            Imgproc.Canny(blurred, mask, 0.5 * otsu, otsu);
            Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_CLOSE, kernel);
            if (debugMaskSink != null) {
                debugMaskSink.accept(opened.clone());
            }
            // RETR_LIST (not EXTERNAL): every card border is a clean rectangle,
            // but they're all joined into one edge mesh by the pocket lines, so
            // the individual cards are *inner* contours. Grab them all, then keep
            // the ones that approximate a 4-corner, card-shaped quad.
            Imgproc.findContours(opened, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            double frameArea = (double) small.rows() * small.cols();
            List<Scored> candidates = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                MatOfPoint2f c2f = new MatOfPoint2f(contour.toArray());
                RotatedRect rr = Imgproc.minAreaRect(c2f);
                c2f.release();
                // minAreaRect bounds the whole border ring, so its area is the
                // full card area even though the contour itself is a thin ring
                // (contourArea would read ~0 for such a ring). This is the robust
                // size signal: full cards pass, smaller art/text boxes don't.
                double rectArea = rr.size.area();
                if (rectArea < frameArea * MIN_AREA_FRACTION || rectArea > frameArea * MAX_AREA_FRACTION) {
                    continue;
                }
                Point[] pts = new Point[4];
                rr.points(pts);
                if (!isPlausibleCardShape(pts)) {
                    continue;
                }
                Rect boundSmall = rr.boundingRect();
                // Map the rect's bounding box back to full resolution + pad.
                Rect box = toPaddedFullRes(boundSmall, scale, sourceBgr.cols(), sourceBgr.rows());
                if (box.width > 0 && box.height > 0) {
                    candidates.add(new Scored(box, rectArea));
                }
            }
            return nonMaxSuppress(candidates);
        } finally {
            small.release();
            gray.release();
            equalized.release();
            blurred.release();
            scratch.release();
            mask.release();
            opened.release();
            hierarchy.release();
            kernel.release();
            clahe.collectGarbage();
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    private record Scored(Rect box, double area) {}

    /**
     * Keeps one box per card. Processes SMALLEST first so tight single-card boxes
     * win; a later box is dropped if it overlaps an already-kept one by more than
     * {@link #OVERLAP_MIN_FRACTION} of the smaller box. That both dedupes nested
     * border/sleeve rectangles and discards merged super-boxes (a 2x2 block
     * largely contains the single cards already kept, so it's suppressed).
     */
    private List<Rect> nonMaxSuppress(List<Scored> candidates) {
        candidates.sort((a, b) -> Double.compare(a.area(), b.area()));
        List<Rect> kept = new ArrayList<>();
        for (Scored s : candidates) {
            boolean overlaps = false;
            for (Rect k : kept) {
                if (overlapFractionOfSmaller(s.box(), k) > OVERLAP_MIN_FRACTION) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(s.box());
            }
        }
        return kept;
    }

    /** Intersection area as a fraction of the smaller box's area (0..1). */
    private static double overlapFractionOfSmaller(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        int iw = Math.max(0, x2 - x1);
        int ih = Math.max(0, y2 - y1);
        double inter = (double) iw * ih;
        double smaller = Math.min((double) a.width * a.height, (double) b.width * b.height);
        return smaller <= 0 ? 0 : inter / smaller;
    }

    private Rect toPaddedFullRes(Rect small, double scale, int fullW, int fullH) {
        double px = small.x / scale;
        double py = small.y / scale;
        double pw = small.width / scale;
        double ph = small.height / scale;
        double padX = pw * CROP_PADDING_FRACTION;
        double padY = ph * CROP_PADDING_FRACTION;
        int x = (int) Math.max(0, Math.floor(px - padX));
        int y = (int) Math.max(0, Math.floor(py - padY));
        int w = (int) Math.min(fullW - x, Math.ceil(pw + 2 * padX));
        int h = (int) Math.min(fullH - y, Math.ceil(ph + 2 * padY));
        return new Rect(x, y, w, h);
    }

    /** Width/height ratio close to the physical MTG card ratio, generous tolerance. */
    private boolean isPlausibleCardShape(Point[] pts) {
        double s01 = distance(pts[0], pts[1]);
        double s12 = distance(pts[1], pts[2]);
        double s23 = distance(pts[2], pts[3]);
        double s30 = distance(pts[3], pts[0]);
        double a = (s01 + s23) / 2.0;
        double b = (s12 + s30) / 2.0;
        if (a <= 0 || b <= 0) {
            return false;
        }
        double ratio = Math.min(a, b) / Math.max(a, b); // always <= 1 (short/long)
        return ratio >= CARD_ASPECT_RATIO * 0.78 && ratio <= CARD_ASPECT_RATIO * 1.25;
    }

    private static double distance(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private Mat bufferedImageToMat(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        MatOfByte bytes = new MatOfByte(output.toByteArray());
        try {
            return Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR);
        } finally {
            bytes.release();
        }
    }

    private BufferedImage matToBufferedImage(Mat mat) throws IOException {
        MatOfByte encoded = new MatOfByte();
        try {
            Imgcodecs.imencode(".png", mat, encoded);
            return ImageIO.read(new ByteArrayInputStream(encoded.toArray()));
        } finally {
            encoded.release();
        }
    }
}
