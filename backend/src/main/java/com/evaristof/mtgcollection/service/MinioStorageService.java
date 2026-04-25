package com.evaristof.mtgcollection.service;

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
import java.io.InputStream;

/**
 * Abstracts MinIO operations: bucket initialisation, upload, existence check
 * and download. All paths inside the bucket follow the convention
 * {@code <setName> - <setCode>/<collectorNumber>-<cardName>.png}.
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;
    private final String bucket;

    public MinioStorageService(MinioClient minioClient,
                               @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket '{}'", bucket);
            }
        } catch (Exception e) {
            log.warn("Could not ensure MinIO bucket '{}' exists — "
                    + "card images will be fetched from Scryfall on every request "
                    + "until MinIO becomes available: {}", bucket, e.getMessage());
        }
    }

    /**
     * Builds the object key used inside the bucket.
     *
     * @param setName         human-readable set name (e.g. "Core Set 2020")
     * @param setCode         Scryfall set code (e.g. "m20")
     * @param collectorNumber collector number (e.g. "150")
     * @param cardName        card name (e.g. "Lightning Bolt")
     * @return object key such as {@code Core Set 2020 - m20/150-Lightning Bolt.png}
     */
    public String objectKey(String setName, String setCode, String collectorNumber, String cardName) {
        String safeSetName = sanitise(setName);
        String safeCardName = sanitise(cardName);
        return safeSetName + " - " + setCode + "/" + collectorNumber + "-" + safeCardName + ".png";
    }

    /**
     * Returns {@code true} if the object already exists in MinIO.
     */
    public boolean exists(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
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

    /**
     * Uploads the image bytes to MinIO.
     */
    public void upload(String objectKey, byte[] imageData) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(bais, imageData.length, -1)
                    .contentType("image/png")
                    .build());
            log.debug("Uploaded '{}' to MinIO ({} bytes)", objectKey, imageData.length);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload '" + objectKey + "' to MinIO", e);
        }
    }

    /**
     * Downloads the image from MinIO and returns all bytes.
     */
    public byte[] download(String objectKey) {
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download '" + objectKey + "' from MinIO", e);
        }
    }

    private static String sanitise(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }
}
