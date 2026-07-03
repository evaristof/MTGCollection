package com.evaristof.mtgcollection.service;

import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
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
 * Detects the physical card's rectangular outline in a raw photo and warps it
 * to a flat, upright crop at the card's true aspect ratio (63x88mm) — the
 * classic "document scanner" OpenCV recipe (blur, edge-detect, find the
 * largest 4-point contour, perspective-warp).
 *
 * <p>This is what makes the rest of the scanner pipeline (title/footer OCR
 * crops, art-region ORB matching) comparable to Scryfall's reference images,
 * which are already tightly cropped to the card border. Phone photos rarely
 * are: they have rotation, keystone distortion, and background.
 *
 * <p>Fails open: if no confident quadrilateral is found (busy background,
 * card cut off at the frame edge, etc.), returns the original image
 * unchanged rather than risking a bad crop.
 */
@Service
public class CardPerspectiveService {

    static {
        OpenCV.loadLocally();
        // OpenCV's internal thread pool can reorder floating-point
        // reductions (e.g. which keypoints "win" a tie when ORB caps
        // features, or RANSAC's internal sampling) — that made repeated
        // scans of the exact same photo return different matches. Forcing
        // single-threaded execution trades a bit of latency for
        // deterministic results, which matters more for a scanner users
        // expect to behave consistently.
        Core.setNumThreads(1);
    }

    private static final Logger log = LoggerFactory.getLogger(CardPerspectiveService.class);

    // Physical MTG card ratio (63mm x 88mm).
    private static final double CARD_ASPECT_RATIO = 63.0 / 88.0;
    private static final int OUTPUT_HEIGHT = 1000;
    private static final int OUTPUT_WIDTH = (int) Math.round(OUTPUT_HEIGHT * CARD_ASPECT_RATIO);

    // Contour search runs on a downscaled copy for speed; corners are mapped
    // back to full resolution before warping so the output stays sharp.
    private static final int CONTOUR_SEARCH_MAX_DIM = 1000;
    // The card must plausibly fill a meaningful chunk of the frame — filters
    // out small spurious rectangles (table edges, phone case, etc).
    private static final double MIN_CONTOUR_AREA_FRACTION = 0.15;

    public BufferedImage correctPerspective(BufferedImage source) {
        Mat sourceMat = null;
        try {
            sourceMat = bufferedImageToMat(source);
            Point[] corners = detectCardCorners(sourceMat);
            if (corners == null) {
                log.info("No reliable card contour detected; using original photo uncorrected");
                return source;
            }
            Mat warped = warp(sourceMat, corners);
            try {
                return matToBufferedImage(warped);
            } finally {
                warped.release();
            }
        } catch (Exception e) {
            log.warn("Perspective correction failed, falling back to original photo: {}", e.getMessage());
            return source;
        } finally {
            if (sourceMat != null) {
                sourceMat.release();
            }
        }
    }

    /**
     * Returns the dilated Canny edge map used for contour search, purely for
     * offline calibration/debugging of the detection thresholds — not used
     * by the production pipeline.
     */
    public BufferedImage debugEdgeMap(BufferedImage source) throws IOException {
        Mat sourceMat = bufferedImageToMat(source);
        Mat small = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat dilated = new Mat();
        Mat kernel = Mat.ones(3, 3, CvType.CV_8U);
        try {
            double scale = Math.min(1.0,
                    (double) CONTOUR_SEARCH_MAX_DIM / Math.max(sourceMat.rows(), sourceMat.cols()));
            Imgproc.resize(sourceMat, small, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.bilateralFilter(gray, blurred, 9, 75, 75);
            Imgproc.Canny(blurred, edges, 40, 120);
            Imgproc.dilate(edges, dilated, kernel);
            return matToBufferedImage(dilated);
        } finally {
            sourceMat.release();
            small.release();
            gray.release();
            blurred.release();
            edges.release();
            dilated.release();
            kernel.release();
        }
    }

    private Point[] detectCardCorners(Mat sourceBgr) {
        double scale = Math.min(1.0,
                (double) CONTOUR_SEARCH_MAX_DIM / Math.max(sourceBgr.rows(), sourceBgr.cols()));
        Mat small = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat dilated = new Mat();
        Mat hierarchy = new Mat();
        Mat kernel = Mat.ones(3, 3, CvType.CV_8U);
        List<MatOfPoint> contours = new ArrayList<>();
        Point[] bestCandidate = null;
        try {
            Imgproc.resize(sourceBgr, small, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY);
            // Bilateral filter smooths textured backgrounds (leather binder
            // pages, table grain) while keeping the card's border edge
            // sharp — plain Gaussian blur left enough high-frequency texture
            // noise to confuse contour detection on textured backgrounds.
            Imgproc.bilateralFilter(gray, blurred, 9, 75, 75);
            Imgproc.Canny(blurred, edges, 40, 120);
            Imgproc.dilate(edges, dilated, kernel);
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            double frameArea = (double) small.rows() * small.cols();
            double bestArea = 0;
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area < frameArea * MIN_CONTOUR_AREA_FRACTION || area <= bestArea) {
                    continue;
                }
                // minAreaRect tolerates broken/noisy contour boundaries far
                // better than requiring approxPolyDP to land on exactly 4
                // points — real photos rarely produce a perfectly closed
                // rectangular contour (glare, motion blur, sleeve seams all
                // put small gaps in the Canny edge), so demanding an exact
                // 4-point polygon rejected almost every real card contour.
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                org.opencv.core.RotatedRect rotatedRect = Imgproc.minAreaRect(contour2f);
                contour2f.release();

                Point[] rectPoints = new Point[4];
                rotatedRect.points(rectPoints);
                Point[] ordered = orderCorners(rectPoints);
                // Reject candidates that don't plausibly look like a
                // 63x88mm card — filters out spurious rectangles found in
                // textured backgrounds (sleeve edges, binder pockets, table
                // grain) that happen to bound a 4-point shape but the wrong
                // proportions.
                if (isPlausibleCardShape(ordered)) {
                    bestCandidate = ordered;
                    bestArea = area;
                }
            }

            if (bestCandidate == null) {
                return null;
            }
            Point[] fullRes = new Point[4];
            for (int i = 0; i < 4; i++) {
                fullRes[i] = new Point(bestCandidate[i].x / scale, bestCandidate[i].y / scale);
            }
            return fullRes;
        } finally {
            small.release();
            gray.release();
            blurred.release();
            edges.release();
            dilated.release();
            hierarchy.release();
            kernel.release();
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    /**
     * Checks the ordered quad's width/height ratio against the physical MTG
     * card ratio (63x88mm ≈ 0.716), with generous tolerance for perspective
     * distortion and corner-detection noise.
     */
    private boolean isPlausibleCardShape(Point[] ordered) {
        double widthTop = distance(ordered[0], ordered[1]);
        double widthBottom = distance(ordered[3], ordered[2]);
        double heightLeft = distance(ordered[0], ordered[3]);
        double heightRight = distance(ordered[1], ordered[2]);
        double width = (widthTop + widthBottom) / 2.0;
        double height = (heightLeft + heightRight) / 2.0;
        if (width <= 0 || height <= 0) {
            return false;
        }
        double ratio = width / height;
        return ratio >= CARD_ASPECT_RATIO * 0.72 && ratio <= CARD_ASPECT_RATIO * 1.28;
    }

    /**
     * Orders 4 arbitrary corner points as top-left, top-right, bottom-right,
     * bottom-left, then rotates that assignment if the quad is wider than it
     * is tall (card photographed sideways) so the output still comes out in
     * portrait orientation.
     */
    private Point[] orderCorners(Point[] points) {
        Point tl = points[0];
        Point br = points[0];
        Point tr = points[0];
        Point bl = points[0];
        double minSum = Double.MAX_VALUE;
        double maxSum = -Double.MAX_VALUE;
        double minDiff = Double.MAX_VALUE;
        double maxDiff = -Double.MAX_VALUE;
        for (Point p : points) {
            double sum = p.x + p.y;
            double diff = p.y - p.x;
            if (sum < minSum) {
                minSum = sum;
                tl = p;
            }
            if (sum > maxSum) {
                maxSum = sum;
                br = p;
            }
            if (diff < minDiff) {
                minDiff = diff;
                tr = p;
            }
            if (diff > maxDiff) {
                maxDiff = diff;
                bl = p;
            }
        }

        double widthTop = distance(tl, tr);
        double widthBottom = distance(bl, br);
        double heightLeft = distance(tl, bl);
        double heightRight = distance(tr, br);
        double maxWidth = Math.max(widthTop, widthBottom);
        double maxHeight = Math.max(heightLeft, heightRight);

        if (maxWidth > maxHeight) {
            // Landscape quad — the card was likely photographed sideways.
            // Rotate the corner assignment 90 degrees so the warp target
            // (fixed portrait aspect ratio) still receives a sensible mapping.
            return new Point[] {bl, tl, tr, br};
        }
        return new Point[] {tl, tr, br, bl};
    }

    private double distance(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private Mat warp(Mat sourceBgr, Point[] orderedCorners) {
        MatOfPoint2f src = new MatOfPoint2f(orderedCorners);
        MatOfPoint2f dst = new MatOfPoint2f(
                new Point(0, 0),
                new Point(OUTPUT_WIDTH - 1, 0),
                new Point(OUTPUT_WIDTH - 1, OUTPUT_HEIGHT - 1),
                new Point(0, OUTPUT_HEIGHT - 1));
        Mat transform = null;
        Mat output = new Mat();
        try {
            transform = Imgproc.getPerspectiveTransform(src, dst);
            Imgproc.warpPerspective(sourceBgr, output, transform, new Size(OUTPUT_WIDTH, OUTPUT_HEIGHT));
            return output;
        } finally {
            src.release();
            dst.release();
            if (transform != null) {
                transform.release();
            }
        }
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
