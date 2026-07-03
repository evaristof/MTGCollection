package com.evaristof.mtgcollection.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TesseractOcrServiceTest {

    @Test
    void isAvailable_falseWhenBinaryMissing() {
        TesseractOcrService service = new TesseractOcrService("this-binary-does-not-exist", true);

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_falseWhenDisabled() {
        TesseractOcrService service = new TesseractOcrService("tesseract", false);

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void recognize_emptyWhenBinaryMissing() {
        TesseractOcrService service = new TesseractOcrService("this-binary-does-not-exist", true);
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);

        Optional<String> result = service.recognize(image, "7");

        assertThat(result).isEmpty();
    }
}
