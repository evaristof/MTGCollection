package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import dev.brachtendorf.jimagehash.hash.Hash;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CardImageMatchService {

    private static final Logger log = LoggerFactory.getLogger(CardImageMatchService.class);
    private static final double MATCH_THRESHOLD = 0.3;
    private static final int HASH_BIT_RESOLUTION = 64;

    private final CardImageHashRepository hashRepository;
    private final MagicSetRepository setRepository;
    private final MinioStorageService minioStorage;
    private final ScryfallHttpClient scryfallClient;
    private final Gson gson;
    private final HttpClient imageHttpClient;

    public record MatchResult(CardImageHash card, double confidence) {}

    public CardImageMatchService(CardImageHashRepository hashRepository,
                                 MagicSetRepository setRepository,
                                 MinioStorageService minioStorage,
                                 ScryfallHttpClient scryfallClient,
                                 Gson gson) {
        this.hashRepository = hashRepository;
        this.setRepository = setRepository;
        this.minioStorage = minioStorage;
        this.scryfallClient = scryfallClient;
        this.gson = gson;
        this.imageHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PreDestroy
    void closeHttpClient() {
        imageHttpClient.close();
    }

    public String computeHash(BufferedImage image) {
        PerceptiveHash hasher = new PerceptiveHash(HASH_BIT_RESOLUTION);
        Hash hash = hasher.hash(image);
        return hash.getHashValue().toString(16);
    }

    public MatchResult findBestMatch(BufferedImage uploadedImage) {
        PerceptiveHash hasher = new PerceptiveHash(HASH_BIT_RESOLUTION);
        Hash uploadedHash = hasher.hash(uploadedImage);
        BigInteger uploadedValue = uploadedHash.getHashValue();
        int actualBitLength = uploadedHash.getBitResolution();

        List<CardImageHash> allHashes = hashRepository.findAll();
        if (allHashes.isEmpty()) {
            return null;
        }

        CardImageHash bestMatch = null;
        double bestDistance = Double.MAX_VALUE;

        for (CardImageHash candidate : allHashes) {
            BigInteger candidateValue = new BigInteger(candidate.getPHash(), 16);
            double distance = normalizedHammingDistance(uploadedValue, candidateValue, actualBitLength);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = candidate;
            }
        }

        if (bestDistance > MATCH_THRESHOLD) {
            return null;
        }

        double confidence = Math.round((1.0 - bestDistance) * 100.0) / 100.0;
        return new MatchResult(bestMatch, confidence);
    }

    public Optional<CardImageHash> findHashBySetAndNumber(String setCode, String collectorNumber) {
        return hashRepository.findBySetCodeAndCollectorNumber(setCode, collectorNumber);
    }

    @Async
    public void syncImagesFromScryfallAsync(String setCode) {
        syncImagesFromScryfall(setCode);
    }

    public void syncImagesFromScryfall(String setCode) {
        try {
            String searchPath = "/cards/search?q=" +
                    URLEncoder.encode("set:" + setCode, StandardCharsets.UTF_8) +
                    "&unique=prints";

            String currentPage = searchPath;
            while (currentPage != null) {
                currentPage = processSearchPage(currentPage, setCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sync interrupted for set: " + setCode, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sync images for set: " + setCode, e);
        }
    }

    private String processSearchPage(String path, String setCode) throws IOException, InterruptedException {
        String json = scryfallClient.get(path);
        Map<String, Object> response = gson.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null) return null;

        for (Map<String, Object> cardMap : data) {
            try {
                processCard(cardMap, setCode);
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("Failed to process card: {}", e.getMessage());
            }
        }

        Boolean hasMore = (Boolean) response.get("has_more");
        String nextPage = (String) response.get("next_page");
        if (Boolean.TRUE.equals(hasMore) && nextPage != null) {
            Thread.sleep(100);
            return nextPage;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void processCard(Map<String, Object> cardMap, String setCode) throws IOException, InterruptedException {
        String name = (String) cardMap.get("name");
        String collectorNumber = (String) cardMap.get("collector_number");
        String setName = (String) cardMap.get("set_name");

        if (hashRepository.findBySetCodeAndCollectorNumber(setCode, collectorNumber).isPresent()) {
            log.debug("Hash already exists for {}/{}, skipping", setCode, collectorNumber);
            return;
        }

        Map<String, String> imageUris = (Map<String, String>) cardMap.get("image_uris");
        if (imageUris == null || !imageUris.containsKey("png")) {
            log.debug("No image_uris.png for {}/{}, skipping", setCode, collectorNumber);
            return;
        }

        String imageUrl = imageUris.get("png");
        byte[] imageData = downloadImage(imageUrl);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
        if (image == null) {
            log.warn("Could not decode image for {}/{}", setCode, collectorNumber);
            return;
        }

        String resolvedSetName = setName != null ? setName : resolveSetName(setCode);
        String objectKey = minioStorage.objectKey(
                resolvedSetName, setCode, collectorNumber,
                name != null ? name : "Unknown");
        minioStorage.upload(objectKey, imageData);

        String hash = computeHash(image);
        CardImageHash entity = new CardImageHash();
        entity.setSetCode(setCode);
        entity.setCollectorNumber(collectorNumber);
        entity.setCardName(name != null ? name : "Unknown");
        entity.setPHash(hash);
        entity.setMinioPath(objectKey);
        hashRepository.save(entity);

        log.info("Synced image hash for {}/{} ({})", setCode, collectorNumber, name);
    }

    private String resolveSetName(String setCode) {
        return setRepository.findById(setCode)
                .map(MagicSet::getSetName)
                .orElse(setCode);
    }

    private byte[] downloadImage(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "MTGCollection/0.1")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response = imageHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Image download failed: " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private double normalizedHammingDistance(BigInteger a, BigInteger b, int bitLength) {
        BigInteger xor = a.xor(b);
        int diffBits = xor.bitCount();
        return (double) diffBits / bitLength;
    }
}
