package com.evaristof.mtgcollection.tools;

import com.evaristof.mtgcollection.service.CardPerspectiveService;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Synthetic self-test for {@link CardPerspectiveService}: takes a clean
 * Scryfall reference image (already in MinIO), composites it onto a larger
 * background at a rotation + offset to simulate an imperfect phone photo,
 * then runs it through perspective correction and writes both the synthetic
 * "photo" and the corrected output for visual inspection.
 *
 * <p>This validates the contour-detection/warp logic before real phone
 * photos are available. It is NOT a substitute for testing on real photos
 * (lighting, glare, and true lens distortion aren't simulated here).
 *
 * <p>Run with {@code mvn exec:java
 * -Dexec.mainClass=com.evaristof.mtgcollection.tools.PerspectiveSelfTestTool}.
 */
public final class PerspectiveSelfTestTool {

    private PerspectiveSelfTestTool() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "target/perspective-self-test");
        Files.createDirectories(outDir);

        MinioClient minioClient = MinioClient.builder()
                .endpoint(System.getProperty("minio.endpoint", "http://localhost:9000"))
                .credentials(
                        System.getProperty("minio.access-key", "admin"),
                        System.getProperty("minio.secret-key", "admin123"))
                .build();
        String bucket = System.getProperty("minio.bucket", "mtg-card-images");

        String key = null;
        for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
            key = result.get().objectName();
            break;
        }
        if (key == null) {
            System.err.println("No objects found in bucket '" + bucket + "'");
            return;
        }

        byte[] data;
        try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            data = stream.readAllBytes();
        }
        BufferedImage cardImage = ImageIO.read(new ByteArrayInputStream(data));
        System.out.println("Using reference card: " + key + " (" + cardImage.getWidth() + "x" + cardImage.getHeight() + ")");

        CardPerspectiveService perspectiveService = new CardPerspectiveService();

        int[] rotations = {0, 8, -15, 25};
        for (int angleDeg : rotations) {
            BufferedImage synthetic = compositeRotated(cardImage, angleDeg);
            String suffix = "rot" + angleDeg;
            ImageIO.write(synthetic, "png", new File(outDir.toFile(), suffix + "-input.png"));

            long start = System.currentTimeMillis();
            BufferedImage corrected = perspectiveService.correctPerspective(synthetic);
            long elapsedMs = System.currentTimeMillis() - start;

            ImageIO.write(corrected, "png", new File(outDir.toFile(), suffix + "-output.png"));
            boolean changed = corrected.getWidth() != synthetic.getWidth() || corrected.getHeight() != synthetic.getHeight();
            System.out.println(suffix + ": corrected=" + corrected.getWidth() + "x" + corrected.getHeight()
                    + " (changed=" + changed + ", " + elapsedMs + "ms)");
        }
        System.out.println("Output written to " + outDir.toAbsolutePath());
    }

    /**
     * Places the card image, rotated by {@code angleDeg}, onto a larger
     * textured background — simulating a phone photo where the card doesn't
     * fill the frame and isn't perfectly aligned.
     */
    private static BufferedImage compositeRotated(BufferedImage card, int angleDeg) {
        int margin = card.getWidth() / 2;
        int canvasW = card.getWidth() + margin * 2;
        int canvasH = card.getHeight() + margin * 2;

        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // Textured, non-uniform background so edge detection has to work for
        // its answer rather than trivially finding a solid-color boundary.
        for (int y = 0; y < canvasH; y += 20) {
            g.setColor(new Color(60 + (y % 40), 55 + (y % 30), 50 + (y % 25)));
            g.fillRect(0, y, canvasW, 20);
        }

        AffineTransform tx = new AffineTransform();
        tx.translate(canvasW / 2.0, canvasH / 2.0);
        tx.rotate(Math.toRadians(angleDeg));
        tx.translate(-card.getWidth() / 2.0, -card.getHeight() / 2.0);
        g.drawImage(card, tx, null);
        g.dispose();
        return canvas;
    }
}
