package com.evaristof.mtgcollection.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the {@code tesseract} CLI binary as an external subprocess. Deliberately
 * avoids JNA/JNI bindings (e.g. Tess4J) that load native code in-process: this
 * project already had OpenCV's native memory corrupted once by another
 * in-process native library (DJL/ONNX Runtime) on Windows. Running Tesseract as
 * a separate OS process trades a bit of latency for full isolation.
 */
@Service
public class TesseractOcrService {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);
    private static final Duration VERSION_CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECOGNIZE_TIMEOUT = Duration.ofSeconds(15);

    private final String binaryPath;
    private final boolean enabled;
    private volatile Boolean available;

    public TesseractOcrService(@Value("${ocr.tesseract.binary-path}") String binaryPath,
                               @Value("${ocr.enabled}") boolean enabled) {
        this.binaryPath = binaryPath;
        this.enabled = enabled;
    }

    /**
     * Whether the Tesseract binary was found and responds to {@code --version}.
     * Checked lazily once and cached: if the binary is missing, callers should
     * skip OCR entirely rather than fail the scan.
     */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        Boolean result = available;
        if (result == null) {
            synchronized (this) {
                result = available;
                if (result == null) {
                    result = checkBinary();
                    available = result;
                }
            }
        }
        return result;
    }

    private boolean checkBinary() {
        try {
            Process process = new ProcessBuilder(binaryPath, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(VERSION_CHECK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Tesseract version check timed out for binary '{}'", binaryPath);
                return false;
            }
            boolean ok = process.exitValue() == 0;
            if (!ok) {
                log.warn("Tesseract binary '{}' exited with code {} on --version", binaryPath, process.exitValue());
            }
            return ok;
        } catch (IOException e) {
            log.warn("Tesseract binary not found at '{}' — OCR disabled for this run: {}", binaryPath, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs OCR on the given image crop. Returns empty if Tesseract is
     * unavailable, times out, or produces no usable text — never throws, so
     * callers can treat OCR as an optional signal.
     *
     * @param psm Tesseract page segmentation mode (e.g. "7" for a single text
     *            line), or {@code null} to use Tesseract's default.
     */
    public Optional<String> recognize(BufferedImage image, String psm) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        Path tempInput = null;
        try {
            tempInput = Files.createTempFile("mtg-ocr-", ".png");
            ImageIO.write(image, "png", tempInput.toFile());

            List<String> command = new ArrayList<>();
            command.add(binaryPath);
            command.add(tempInput.toString());
            command.add("stdout");
            if (psm != null) {
                command.add("--psm");
                command.add(psm);
            }

            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            String output;
            try (InputStream stdout = process.getInputStream()) {
                output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
            }

            boolean finished = process.waitFor(RECOGNIZE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Tesseract OCR timed out");
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("Tesseract OCR exited with code {}", process.exitValue());
                return Optional.empty();
            }

            String trimmed = output.strip();
            return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
        } catch (IOException e) {
            log.warn("Tesseract OCR failed: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (tempInput != null) {
                try {
                    Files.deleteIfExists(tempInput);
                } catch (IOException e) {
                    log.debug("Could not delete temp OCR file '{}': {}", tempInput, e.getMessage());
                }
            }
        }
    }
}
