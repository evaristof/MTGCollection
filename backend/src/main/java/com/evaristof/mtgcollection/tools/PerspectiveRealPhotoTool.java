package com.evaristof.mtgcollection.tools;

import com.evaristof.mtgcollection.service.CardNameCatalogService;
import com.evaristof.mtgcollection.service.CardPerspectiveService;
import com.evaristof.mtgcollection.service.TesseractOcrService;
import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.google.gson.Gson;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the real-photo pipeline end to end against actual phone photos of
 * physical cards (not clean Scryfall references): perspective correction,
 * then title/footer OCR crops on the corrected image, then fuzzy name
 * matching. Used to validate {@link CardPerspectiveService} on the messy
 * inputs it's actually meant for (skew, glare, foil, non-English cards).
 *
 * <p>Run with {@code mvn exec:java
 * -Dexec.mainClass=com.evaristof.mtgcollection.tools.PerspectiveRealPhotoTool
 * -Dexec.args="<inputDir> <outputDir>"}.
 */
public final class PerspectiveRealPhotoTool {

    private static final String TESSERACT_PATH = System.getProperty("tesseract.path", "tesseract");

    // Same crop percentages calibrated in ScannerCalibrationTool.
    private static final double TITLE_TOP = 0.052;
    private static final double TITLE_BOTTOM = 0.097;
    private static final double TITLE_LEFT = 0.055;
    private static final double TITLE_RIGHT = 0.85;

    private static final double FOOTER_TOP = 0.936;
    private static final double FOOTER_BOTTOM = 0.978;
    private static final double FOOTER_LEFT = 0.015;
    private static final double FOOTER_RIGHT = 0.24;

    private static final int UPSCALE_FACTOR = 3;

    private PerspectiveRealPhotoTool() {
    }

    public static void main(String[] args) throws Exception {
        Path inDir = Path.of(args.length > 0 ? args[0] : "D:/MtgCollection");
        Path outDir = Path.of(args.length > 1 ? args[1] : "target/real-photo-test");
        Files.createDirectories(outDir);

        CardPerspectiveService perspectiveService = new CardPerspectiveService();
        TesseractOcrService ocr = new TesseractOcrService(TESSERACT_PATH, true);
        if (!ocr.isAvailable()) {
            System.err.println("Tesseract not available at '" + TESSERACT_PATH + "'");
            return;
        }
        ScryfallHttpClient scryfallClient = new ScryfallHttpClient(
                "https://api.scryfall.com", 3, 800, HttpClient.newHttpClient());
        CardNameCatalogService catalog = new CardNameCatalogService(scryfallClient, new Gson());
        catalog.refresh();
        System.out.println("Loaded " + catalog.allNames().size() + " names from Scryfall catalog");

        File[] files = inDir.toFile().listFiles((dir, name) ->
                name.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")
                        || name.toLowerCase(java.util.Locale.ROOT).endsWith(".jpeg")
                        || name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
        if (files == null || files.length == 0) {
            System.err.println("No image files found in " + inDir);
            return;
        }
        java.util.Arrays.sort(files);

        for (File file : files) {
            BufferedImage photo = ImageIO.read(file);
            if (photo == null) {
                System.out.println("[SKIP] Could not decode " + file.getName());
                continue;
            }

            long start = System.currentTimeMillis();
            BufferedImage corrected = perspectiveService.correctPerspective(photo);
            long correctMs = System.currentTimeMillis() - start;
            boolean cornersFound = corrected.getWidth() != photo.getWidth() || corrected.getHeight() != photo.getHeight();

            String stem = file.getName().replaceFirst("\\.[^.]+$", "");
            ImageIO.write(corrected, "png", new File(outDir.toFile(), stem + "-corrected.png"));
            ImageIO.write(perspectiveService.debugEdgeMap(photo), "png", new File(outDir.toFile(), stem + "-edges.png"));

            BufferedImage flipped = rotate180(corrected);

            OcrAttempt normal = tryOcr(corrected, ocr, catalog);
            OcrAttempt upsideDown = tryOcr(flipped, ocr, catalog);
            boolean usedFlipped = upsideDown.bestScore() > normal.bestScore();
            OcrAttempt chosen = usedFlipped ? upsideDown : normal;

            ImageIO.write(chosen.titleCrop(), "png", new File(outDir.toFile(), stem + "-title.png"));
            ImageIO.write(chosen.footerCrop(), "png", new File(outDir.toFile(), stem + "-footer.png"));

            System.out.println("----------------------------------------------------------");
            System.out.println("File:          " + file.getName() + " (" + photo.getWidth() + "x" + photo.getHeight() + ")");
            System.out.println("Corners found: " + cornersFound + " (" + correctMs + "ms)");
            System.out.println("Corrected:     " + corrected.getWidth() + "x" + corrected.getHeight());
            System.out.println("Orientation:   " + (usedFlipped ? "flipped 180" : "normal")
                    + " (normal best=" + normal.bestScore() + ", flipped best=" + upsideDown.bestScore() + ")");
            System.out.println("OCR title:     '" + chosen.titleText() + "'");
            System.out.println("OCR footer:    '" + chosen.footerText() + "'");
            List<CardNameCatalogService.NameMatch> matches = chosen.matches();
            String topMatch = matches.isEmpty() ? "(none)" : matches.get(0).name() + " (" + matches.get(0).score() + ")";
            System.out.println("Fuzzy top:     " + topMatch);
            if (matches.size() > 1) {
                System.out.println("Fuzzy others:  " + matches.subList(1, matches.size()));
            }
        }
        System.out.println("============================================================");
        System.out.println("Output written to " + outDir.toAbsolutePath());
    }

    private record OcrAttempt(String titleText, String footerText, BufferedImage titleCrop, BufferedImage footerCrop,
                              List<CardNameCatalogService.NameMatch> matches) {
        int bestScore() {
            return matches.isEmpty() ? 0 : matches.get(0).score();
        }
    }

    private static OcrAttempt tryOcr(BufferedImage image, TesseractOcrService ocr, CardNameCatalogService catalog) {
        BufferedImage titleCrop = crop(image, TITLE_TOP, TITLE_BOTTOM, TITLE_LEFT, TITLE_RIGHT);
        BufferedImage footerCrop = crop(image, FOOTER_TOP, FOOTER_BOTTOM, FOOTER_LEFT, FOOTER_RIGHT);
        String titleText = ocr.recognize(titleCrop, "7").orElse("");
        String footerText = ocr.recognize(footerCrop, "6").orElse("");
        List<CardNameCatalogService.NameMatch> matches = catalog.fuzzyMatch(titleText, 3);
        return new OcrAttempt(titleText, footerText, titleCrop, footerCrop, matches);
    }

    private static BufferedImage rotate180(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.rotate(Math.PI, w / 2.0, h / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage crop(BufferedImage image, double top, double bottom, double left, double right) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = Math.max(0, (int) (w * left));
        int x1 = Math.min(w, (int) (w * right));
        int y0 = Math.max(0, (int) (h * top));
        int y1 = Math.min(h, (int) (h * bottom));
        BufferedImage sub = image.getSubimage(x0, y0, x1 - x0, y1 - y0);
        return upscale(sub, UPSCALE_FACTOR);
    }

    private static BufferedImage upscale(BufferedImage src, int factor) {
        int newW = src.getWidth() * factor;
        int newH = src.getHeight() * factor;
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }
}
