package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallImageUris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * On-demand card-image service.
 *
 * <p>Flow for {@link #getCardImage(Long)}:
 * <ol>
 *   <li>Look up the {@link CollectionCard} by id.</li>
 *   <li>Build the MinIO object key from the set name, set code,
 *       collector number and card name.</li>
 *   <li>If the image already exists in MinIO, return it.</li>
 *   <li>Otherwise, call {@link CardLookupService} to obtain the Scryfall
 *       card (which now includes {@code image_uris}), download the PNG
 *       <strong>in memory</strong>, store it in MinIO and return the bytes.</li>
 * </ol>
 */
@Service
public class CardImageService {

    private static final Logger log = LoggerFactory.getLogger(CardImageService.class);

    private final CollectionCardRepository cardRepository;
    private final MagicSetRepository setRepository;
    private final CardLookupService cardLookupService;
    private final MinioStorageService minioStorage;
    private final HttpClient httpClient;

    public CardImageService(CollectionCardRepository cardRepository,
                            MagicSetRepository setRepository,
                            CardLookupService cardLookupService,
                            MinioStorageService minioStorage,
                            HttpClient httpClient) {
        this.cardRepository = cardRepository;
        this.setRepository = setRepository;
        this.cardLookupService = cardLookupService;
        this.minioStorage = minioStorage;
        this.httpClient = httpClient;
    }

    /**
     * Returns the PNG image bytes for the given collection-card id.
     * Downloads from Scryfall and caches in MinIO when not yet stored.
     *
     * @param cardId primary-key of {@link CollectionCard}
     * @return PNG image bytes
     * @throws java.util.NoSuchElementException if the card id is unknown
     * @throws IllegalStateException if the card lacks enough data to look up
     * @throws RuntimeException on download / storage failures
     */
    public byte[] getCardImage(Long cardId) {
        CollectionCard card = cardRepository.findById(cardId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "CollectionCard not found: id=" + cardId));

        String setCode = card.getSetCode();
        String collectorNumber = card.getCardNumber();
        String cardName = card.getCardName();

        if (setCode == null || setCode.isBlank()) {
            throw new IllegalStateException(
                    "Card #" + cardId + " has no set_code — cannot look up image");
        }
        if (collectorNumber == null || collectorNumber.isBlank()) {
            throw new IllegalStateException(
                    "Card #" + cardId + " has no collector_number — cannot look up image");
        }

        String setName = resolveSetName(setCode);
        String objectKey = minioStorage.objectKey(setName, setCode, collectorNumber, cardName);

        if (minioStorage.exists(objectKey)) {
            log.debug("Image cache hit for '{}'", objectKey);
            return minioStorage.download(objectKey);
        }

        log.info("Image cache miss for '{}' — downloading from Scryfall", objectKey);

        ScryfallCard scryfallCard = cardLookupService.getCardBySetAndNumber(setCode, collectorNumber);
        ScryfallImageUris uris = scryfallCard.getImageUris();
        if (uris == null || uris.getPng() == null) {
            throw new IllegalStateException(
                    "Scryfall returned no PNG image URL for set=" + setCode
                            + " number=" + collectorNumber);
        }

        byte[] imageBytes = downloadImage(uris.getPng());
        try {
            minioStorage.upload(objectKey, imageBytes);
        } catch (RuntimeException e) {
            log.warn("MinIO upload failed for '{}' — returning image without caching: {}",
                    objectKey, e.getMessage());
        }
        return imageBytes;
    }

    private String resolveSetName(String setCode) {
        return setRepository.findById(setCode)
                .map(MagicSet::getSetName)
                .orElse(setCode);
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
}
