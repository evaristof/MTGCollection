package com.evaristof.mtgcollection.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Reads an image while honouring its EXIF orientation tag.
 *
 * <p>Phone cameras usually store photos in a fixed sensor orientation and record
 * how to rotate them for display in the EXIF {@code Orientation} tag. {@link ImageIO}
 * decodes the raw pixels and ignores that tag, so a photo the user sees upright
 * can arrive here rotated 90°/180°. That breaks card detection and art matching,
 * which assume portrait, upright cards. This helper applies the tag so the rest
 * of the pipeline always works on an upright image.
 */
public final class ImageOrientationUtil {

    private static final Logger log = LoggerFactory.getLogger(ImageOrientationUtil.class);

    private ImageOrientationUtil() {
    }

    /**
     * Decodes {@code bytes} into a {@link BufferedImage} rotated/flipped to match
     * its EXIF orientation. Falls open to a plain {@link ImageIO} decode (no
     * rotation) if the metadata is missing or unreadable.
     */
    public static BufferedImage readUpright(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            return null;
        }
        int orientation = readOrientation(bytes);
        return applyOrientation(image, orientation);
    }

    /** EXIF orientation value (1..8); 1 means "already upright". */
    private static int readOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("No readable EXIF orientation, assuming upright: {}", e.getMessage());
        }
        return 1;
    }

    /** Applies one of the 8 EXIF orientations, returning an upright image. */
    private static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        // Whether the final image is rotated 90°/270° (swaps dimensions).
        boolean swap = orientation >= 5;
        switch (orientation) {
            case 2 -> { // mirror horizontal
                t.scale(-1.0, 1.0);
                t.translate(-w, 0);
            }
            case 3 -> { // rotate 180
                t.translate(w, h);
                t.rotate(Math.PI);
            }
            case 4 -> { // mirror vertical
                t.scale(1.0, -1.0);
                t.translate(0, -h);
            }
            case 5 -> { // transpose (mirror + rotate 90 CW)
                t.rotate(-Math.PI / 2);
                t.scale(-1.0, 1.0);
            }
            case 6 -> { // rotate 90 CW
                t.rotate(Math.PI / 2);
                t.translate(0, -h);
            }
            case 7 -> { // transverse (mirror + rotate 270 CW)
                t.scale(-1.0, 1.0);
                t.translate(-h, 0);
                t.rotate(Math.PI / 2);
                t.translate(0, -h);
            }
            case 8 -> { // rotate 90 CCW (270 CW)
                t.rotate(-Math.PI / 2);
                t.translate(-w, 0);
            }
            default -> {
                return img;
            }
        }
        int newW = swap ? h : w;
        int newH = swap ? w : h;
        BufferedImage dest = new BufferedImage(newW, newH,
                img.getType() == 0 ? BufferedImage.TYPE_INT_RGB : img.getType());
        AffineTransformOp op = new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR);
        op.filter(img, dest);
        return dest;
    }
}
