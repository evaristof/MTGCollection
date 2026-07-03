package com.evaristof.mtgcollection.tools;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.service.CardNameCatalogService;
import com.evaristof.mtgcollection.service.TesseractOcrService;
import com.google.gson.Gson;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;

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
 * Standalone calibration tool for the scanner's title/footer OCR crops. Not
 * wired into the Spring app — runs against the images already sitting in
 * MinIO (from {@code sync-images}) so the crop percentages and OCR settings
 * can be tuned before the real perspective-correction pipeline exists.
 *
 * <p>Run with {@code mvn exec:java -Dexec.mainClass=com.evaristof.mtgcollection.tools.ScannerCalibrationTool}.
 * Crops are written to the directory given as the first argument (defaults to
 * {@code target/calibration}) for visual inspection.
 */
public final class ScannerCalibrationTool {

    private static final int SAMPLE_SIZE = 8;
    private static final String TESSERACT_PATH = System.getProperty("tesseract.path", "tesseract");

    // Percentages of the (already border-cropped) card image. Tuned against
    // the standard Scryfall PNG frame layout; revisit if OCR misses text.
    private static final double TITLE_TOP = 0.052;
    private static final double TITLE_BOTTOM = 0.097;
    private static final double TITLE_LEFT = 0.055;
    private static final double TITLE_RIGHT = 0.85;

    private static final double FOOTER_TOP = 0.936;
    private static final double FOOTER_BOTTOM = 0.978;
    private static final double FOOTER_LEFT = 0.015;
    private static final double FOOTER_RIGHT = 0.24;

    private static final int UPSCALE_FACTOR = 3;

    private ScannerCalibrationTool() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "target/calibration");
        Files.createDirectories(outDir);

        TesseractOcrService ocr = new TesseractOcrService(TESSERACT_PATH, true);
        if (!ocr.isAvailable()) {
            System.err.println("Tesseract not available at '" + TESSERACT_PATH
                    + "' — pass -Dtesseract.path=<full path> if it's not on PATH.");
            return;
        }

        ScryfallHttpClient scryfallClient = new ScryfallHttpClient(
                "https://api.scryfall.com", 3, 800, HttpClient.newHttpClient());
        CardNameCatalogService catalog = new CardNameCatalogService(scryfallClient, new Gson());
        catalog.refresh();
        System.out.println("Loaded " + catalog.allNames().size() + " names from Scryfall catalog");

        MinioClient minioClient = MinioClient.builder()
                .endpoint(System.getProperty("minio.endpoint", "http://localhost:9000"))
                .credentials(
                        System.getProperty("minio.access-key", "admin"),
                        System.getProperty("minio.secret-key", "admin123"))
                .build();
        String bucket = System.getProperty("minio.bucket", "mtg-card-images");

        List<String> keys = listKeys(minioClient, bucket, SAMPLE_SIZE);
        System.out.println("Sampling " + keys.size() + " objects from bucket '" + bucket + "'");

        int correct = 0;
        for (String key : keys) {
            byte[] data;
            try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                data = stream.readAllBytes();
            }
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (image == null) {
                System.out.println("[SKIP] Could not decode " + key);
                continue;
            }

            String expectedName = expectedNameFromKey(key);
            String safeName = key.replaceAll("[/\\\\]", "_");

            BufferedImage titleCrop = crop(image, TITLE_TOP, TITLE_BOTTOM, TITLE_LEFT, TITLE_RIGHT);
            BufferedImage footerCrop = crop(image, FOOTER_TOP, FOOTER_BOTTOM, FOOTER_LEFT, FOOTER_RIGHT);
            ImageIO.write(titleCrop, "png", new File(outDir.toFile(), safeName + "-title.png"));
            ImageIO.write(footerCrop, "png", new File(outDir.toFile(), safeName + "-footer.png"));

            String titleText = ocr.recognize(titleCrop, "7").orElse("");
            String footerText = ocr.recognize(footerCrop, "6").orElse("");
            String[] expectedSetNumber = expectedSetAndNumberFromKey(key);
            String[] parsedFooter = parseFooter(footerText);

            List<CardNameCatalogService.NameMatch> matches = catalog.fuzzyMatch(titleText, 3);
            String topMatch = matches.isEmpty() ? "(none)" : matches.get(0).name() + " (" + matches.get(0).score() + ")";
            boolean isCorrect = !matches.isEmpty() && matches.get(0).name().equalsIgnoreCase(expectedName);
            if (isCorrect) correct++;

            System.out.println("----------------------------------------------------------");
            System.out.println("Object:        " + key);
            System.out.println("Expected name: " + expectedName);
            System.out.println("Expected #/set:" + expectedSetNumber[0] + " / " + expectedSetNumber[1]);
            System.out.println("Parsed #/set:  " + parsedFooter[0] + " / " + parsedFooter[1]);
            System.out.println("OCR title:     '" + titleText + "'");
            System.out.println("OCR footer:    '" + footerText + "'");
            System.out.println("Fuzzy top:     " + topMatch + (isCorrect ? "  [OK]" : "  [MISS]"));
            if (matches.size() > 1) {
                System.out.println("Fuzzy others:  " + matches.subList(1, matches.size()));
            }
        }
        System.out.println("============================================================");
        System.out.println("Correct: " + correct + "/" + keys.size());
        System.out.println("Crops written to " + outDir.toAbsolutePath());
    }

    private static List<String> listKeys(MinioClient client, String bucket, int limit) throws Exception {
        List<String> keys = new java.util.ArrayList<>();
        for (Result<Item> result : client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
            keys.add(result.get().objectName());
            if (keys.size() >= limit) break;
        }
        return keys;
    }

    private static String expectedNameFromKey(String key) {
        int slash = key.indexOf('/');
        String file = slash >= 0 ? key.substring(slash + 1) : key;
        String stem = file.replaceFirst("\\.[^.]+$", "");
        int dash = stem.indexOf('-');
        return dash >= 0 ? stem.substring(dash + 1) : stem;
    }

    private static String[] expectedSetAndNumberFromKey(String key) {
        int slash = key.indexOf('/');
        String folder = slash >= 0 ? key.substring(0, slash) : "";
        String file = slash >= 0 ? key.substring(slash + 1) : key;
        int dashSpace = folder.lastIndexOf(" - ");
        String setCode = dashSpace >= 0 ? folder.substring(dashSpace + 3).trim() : "";
        String stem = file.replaceFirst("\\.[^.]+$", "");
        int dash = stem.indexOf('-');
        String number = dash >= 0 ? stem.substring(0, dash) : "";
        return new String[] {number, setCode};
    }

    private static final java.util.regex.Pattern COLLECTOR_NUMBER = java.util.regex.Pattern.compile("(\\d{1,4})(?:/\\d{1,4})?");
    private static final java.util.regex.Pattern SET_CODE = java.util.regex.Pattern.compile("\\b([A-Z]{2,5})\\b");

    private static String[] parseFooter(String footerText) {
        String number = "";
        String setCode = "";
        var numMatcher = COLLECTOR_NUMBER.matcher(footerText);
        if (numMatcher.find()) {
            number = numMatcher.group(1);
        }
        var setMatcher = SET_CODE.matcher(footerText.toUpperCase(java.util.Locale.ROOT));
        while (setMatcher.find()) {
            String candidate = setMatcher.group(1);
            if (!candidate.equals("EN") && !candidate.equals("R") && !candidate.equals("U")
                    && !candidate.equals("C") && !candidate.equals("M")) {
                setCode = candidate;
                break;
            }
        }
        return new String[] {number, setCode};
    }

    private static BufferedImage crop(BufferedImage image, double top, double bottom, double left, double right) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = (int) (w * left);
        int x1 = (int) (w * right);
        int y0 = (int) (h * top);
        int y1 = (int) (h * bottom);
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
