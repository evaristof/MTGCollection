package com.evaristof.mtgcollection.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class CardPerspectiveServiceTest {

    private final CardPerspectiveService service = new CardPerspectiveService();

    @Test
    void correctPerspective_returnsOriginalWhenNoContourFound() {
        BufferedImage blank = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, 200, 200);
        g.dispose();

        BufferedImage result = service.correctPerspective(blank);

        assertThat(result).isSameAs(blank);
    }

    @Test
    void correctPerspective_warpsRotatedCardToUprightCrop() {
        BufferedImage synthetic = compositeRotatedCard();

        BufferedImage result = service.correctPerspective(synthetic);

        // A successful warp always produces the fixed card-ratio output
        // size, regardless of the input photo's dimensions.
        assertThat(result).isNotSameAs(synthetic);
        assertThat(result.getWidth()).isNotEqualTo(synthetic.getWidth());
        assertThat((double) result.getWidth() / result.getHeight())
                .isCloseTo(63.0 / 88.0, org.assertj.core.data.Offset.offset(0.05));
    }

    /**
     * Draws a high-contrast, card-proportioned rectangle at a slight
     * rotation on a larger textured background — mimicking a phone photo
     * where the card doesn't fill the frame and isn't perfectly aligned.
     */
    private BufferedImage compositeRotatedCard() {
        int cardWidth = 300;
        int cardHeight = (int) Math.round(cardWidth * 88.0 / 63.0);
        BufferedImage card = new BufferedImage(cardWidth, cardHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D cardG = card.createGraphics();
        cardG.setColor(Color.WHITE);
        cardG.fillRect(0, 0, cardWidth, cardHeight);
        cardG.setColor(Color.BLACK);
        cardG.fillRect(10, 10, cardWidth - 20, cardHeight - 20);
        cardG.setColor(Color.WHITE);
        cardG.fillRect(30, 30, cardWidth - 60, cardHeight - 60);
        cardG.dispose();

        int margin = cardWidth / 2;
        int canvasW = cardWidth + margin * 2;
        int canvasH = cardHeight + margin * 2;
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(90, 80, 70));
        g.fillRect(0, 0, canvasW, canvasH);

        AffineTransform tx = new AffineTransform();
        tx.translate(canvasW / 2.0, canvasH / 2.0);
        tx.rotate(Math.toRadians(10));
        tx.translate(-cardWidth / 2.0, -cardHeight / 2.0);
        g.drawImage(card, tx, null);
        g.dispose();
        return canvas;
    }
}
