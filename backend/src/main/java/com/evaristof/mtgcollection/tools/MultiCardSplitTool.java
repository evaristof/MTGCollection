package com.evaristof.mtgcollection.tools;

import com.evaristof.mtgcollection.service.CardPerspectiveService;
import com.evaristof.mtgcollection.service.CardSplitterService;
import com.evaristof.mtgcollection.service.ImageOrientationUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Offline calibration tool for {@link CardSplitterService}: runs the multi-card
 * splitter on a photo and writes each crop to disk so the detection thresholds
 * can be eyeballed. Not part of the runtime app.
 *
 * <pre>
 *   mvn -o exec:java -Dexec.mainClass=com.evaristof.mtgcollection.tools.MultiCardSplitTool \
 *       -Dexec.args="D:/MtgCollection/binder1.jpg D:/MtgCollection/split_out"
 * </pre>
 */
public class MultiCardSplitTool {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "D:/MtgCollection/binder1.jpg";
        String outDir = args.length > 1 ? args[1] : "D:/MtgCollection/split_out";

        // Read honouring EXIF orientation (phone photos), same as production.
        BufferedImage src = ImageOrientationUtil.readUpright(Files.readAllBytes(new File(path).toPath()));
        if (src == null) {
            System.err.println("Could not read image: " + path);
            return;
        }
        System.out.printf("Upright input: %dx%d%n", src.getWidth(), src.getHeight());

        // Instantiating CardPerspectiveService loads OpenCV (its static block).
        CardPerspectiveService perspective = new CardPerspectiveService();
        CardSplitterService splitter = new CardSplitterService(perspective);

        File dbgDir = new File(outDir);
        //noinspection ResultOfMethodCallIgnored
        dbgDir.mkdirs();
        splitter.setDebugMaskSink(mask -> {
            try {
                org.opencv.imgcodecs.Imgcodecs.imwrite(new File(dbgDir, "mask.png").getAbsolutePath(), mask);
            } finally {
                mask.release();
            }
        });

        List<BufferedImage> crops = splitter.splitCards(src);
        System.out.printf("Input %dx%d -> detected %d crop(s)%n",
                src.getWidth(), src.getHeight(), crops.size());

        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Could not create output dir: " + outDir);
            return;
        }
        // Clear old crops from a previous run.
        File[] old = dir.listFiles((d, n) -> n.startsWith("crop_") && n.endsWith(".png"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        for (int i = 0; i < crops.size(); i++) {
            BufferedImage c = crops.get(i);
            File f = new File(dir, String.format("crop_%02d_%dx%d.png", i, c.getWidth(), c.getHeight()));
            ImageIO.write(c, "png", f);
            System.out.printf("  crop %02d: %dx%d -> %s%n", i, c.getWidth(), c.getHeight(), f.getName());
        }
        writeContactSheet(crops, new File(dir, "contact_sheet.png"));
        System.out.println("Wrote crops to " + dir.getAbsolutePath());
    }

    /** Composes all crops into a single labelled grid image for quick review. */
    private static void writeContactSheet(List<BufferedImage> crops, File out) throws Exception {
        if (crops.isEmpty()) {
            return;
        }
        int cols = Math.min(4, crops.size());
        int rows = (crops.size() + cols - 1) / cols;
        int cellW = 240, cellH = 340, pad = 10, label = 18;
        int w = cols * cellW + (cols + 1) * pad;
        int h = rows * (cellH + label) + (rows + 1) * pad;
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = sheet.createGraphics();
        g.setColor(new java.awt.Color(30, 30, 30));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int i = 0; i < crops.size(); i++) {
            int r = i / cols, c = i % cols;
            int x = pad + c * (cellW + pad);
            int y = pad + r * (cellH + label + pad);
            BufferedImage img = crops.get(i);
            double s = Math.min((double) cellW / img.getWidth(), (double) cellH / img.getHeight());
            int dw = (int) (img.getWidth() * s), dh = (int) (img.getHeight() * s);
            g.drawImage(img, x + (cellW - dw) / 2, y + (cellH - dh) / 2, dw, dh, null);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(String.format("#%02d  %dx%d", i, img.getWidth(), img.getHeight()),
                    x, y + cellH + label - 4);
        }
        g.dispose();
        ImageIO.write(sheet, "png", out);
        System.out.println("Wrote contact sheet: " + out.getAbsolutePath());
    }
}
