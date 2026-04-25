package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Downloads set icon SVGs from Scryfall and stores them in a dedicated
 * MinIO bucket ({@code mtg-set-images}).
 */
@Service
public class SetImageService {

    private static final Logger log = LoggerFactory.getLogger(SetImageService.class);

    private static final String SET_IMAGE_BUCKET = "mtg-set-images";

    private final MinioClient minioClient;
    private final MagicSetRepository setRepository;
    private final HttpClient httpClient;

    public SetImageService(MinioClient minioClient,
                           MagicSetRepository setRepository,
                           HttpClient httpClient) {
        this.minioClient = minioClient;
        this.setRepository = setRepository;
        this.httpClient = httpClient;
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(SET_IMAGE_BUCKET).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(SET_IMAGE_BUCKET).build());
                log.info("Created MinIO bucket '{}'", SET_IMAGE_BUCKET);
            }
        } catch (Exception e) {
            log.warn("Could not ensure MinIO bucket '{}' exists — "
                    + "set icons will be fetched from Scryfall on every request "
                    + "until MinIO becomes available: {}", SET_IMAGE_BUCKET, e.getMessage());
        }
    }

    /**
     * Builds the object key for a set icon inside the bucket.
     * Format: "{code}-{name}.svg"
     */
    public String objectKey(String setCode, String setName) {
        String safeName = sanitise(setName);
        return setCode + "-" + safeName + ".svg";
    }

    /**
     * Downloads set icons from Scryfall for all persisted sets that have an
     * {@code icon_svg_uri} and stores them in MinIO. Skips sets whose icon
     * is already cached.
     *
     * @return number of icons downloaded and stored
     */
    public int syncAllSetIcons() {
        List<MagicSet> sets = setRepository.findAll();
        int downloaded = 0;
        for (MagicSet set : sets) {
            if (set.getIconSvgUri() == null || set.getIconSvgUri().isBlank()) {
                continue;
            }
            String key = objectKey(set.getSetCode(), set.getSetName());
            if (existsInMinio(key)) {
                continue;
            }
            try {
                byte[] svgBytes = downloadImage(set.getIconSvgUri());
                upload(key, svgBytes, "image/svg+xml");
                downloaded++;
                log.debug("Cached set icon '{}' ({} bytes)", key, svgBytes.length);
            } catch (RuntimeException e) {
                log.warn("Failed to download/cache icon for set '{}': {}",
                        set.getSetCode(), e.getMessage());
            }
        }
        log.info("Set icon sync complete: {} new icons cached", downloaded);
        return downloaded;
    }

    /**
     * Returns the SVG bytes for a given set code. Downloads from Scryfall
     * and caches in MinIO on first access.
     */
    public byte[] getSetIcon(String setCode) {
        MagicSet set = setRepository.findById(setCode)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "MagicSet not found: code=" + setCode));

        if (set.getIconSvgUri() == null || set.getIconSvgUri().isBlank()) {
            throw new IllegalStateException(
                    "Set '" + setCode + "' has no icon_svg_uri");
        }

        String key = objectKey(set.getSetCode(), set.getSetName());

        if (existsInMinio(key)) {
            return downloadFromMinio(key);
        }

        byte[] svgBytes = downloadImage(set.getIconSvgUri());
        try {
            upload(key, svgBytes, "image/svg+xml");
        } catch (RuntimeException e) {
            log.warn("MinIO upload failed for set icon '{}' — returning without caching: {}",
                    key, e.getMessage());
        }
        return svgBytes;
    }

    private boolean existsInMinio(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(SET_IMAGE_BUCKET)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            log.warn("MinIO stat failed for '{}': {}", objectKey, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("MinIO stat failed for '{}': {}", objectKey, e.getMessage());
            return false;
        }
    }

    private void upload(String objectKey, byte[] data, String contentType) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(SET_IMAGE_BUCKET)
                    .object(objectKey)
                    .stream(bais, data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload '" + objectKey + "' to MinIO", e);
        }
    }

    private byte[] downloadFromMinio(String objectKey) {
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(SET_IMAGE_BUCKET)
                .object(objectKey)
                .build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download '" + objectKey + "' from MinIO", e);
        }
    }

    private byte[] downloadImage(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " for " + imageUrl);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Image download interrupted: " + imageUrl, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download image: " + imageUrl, e);
        }
    }

    private static String sanitise(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }
}
