package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Fetches the full Scryfall card object for a given (exact name, set code).
 *
 * <p>Wraps {@code GET /cards/named?exact=<name>&set=<code>} and returns the
 * deserialized {@link ScryfallCard} DTO (includes {@code collector_number},
 * {@code type_line}, {@code prices}, ...).</p>
 */
@Service
public class CardLookupService {

    private final ScryfallHttpClient httpClient;
    private final Gson gson;

    public CardLookupService(ScryfallHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    public ScryfallCard getCardByNameAndSet(String cardName, String setCode) {
        if (cardName == null || cardName.isBlank()) {
            throw new IllegalArgumentException("cardName must not be blank");
        }
        if (setCode == null || setCode.isBlank()) {
            throw new IllegalArgumentException("setCode must not be blank");
        }

        String path = urlByNameAndSet(cardName, setCode);
        return fetch(path);
    }

    /** Builds the Scryfall path (relative to base URL) for a name+set lookup. */
    public String urlByNameAndSet(String cardName, String setCode) {
        return "/cards/named?exact=" + URLEncoder.encode(cardName, StandardCharsets.UTF_8)
                + "&set=" + URLEncoder.encode(setCode, StandardCharsets.UTF_8);
    }

    /**
     * Fetches the card identified by the ({@code setCode}, {@code collectorNumber})
     * pair. Uses the {@code GET /cards/{set}/{number}} Scryfall endpoint, which
     * returns the same {@link ScryfallCard} shape as the name-based lookup.
     */
    public ScryfallCard getCardBySetAndNumber(String setCode, String collectorNumber) {
        if (setCode == null || setCode.isBlank()) {
            throw new IllegalArgumentException("setCode must not be blank");
        }
        if (collectorNumber == null || collectorNumber.isBlank()) {
            throw new IllegalArgumentException("collectorNumber must not be blank");
        }

        String path = urlBySetAndNumber(setCode, collectorNumber);
        return fetch(path);
    }

    /** Builds the Scryfall path (relative to base URL) for a set+collector-number lookup. */
    public String urlBySetAndNumber(String setCode, String collectorNumber) {
        return "/cards/" + URLEncoder.encode(setCode, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(collectorNumber, StandardCharsets.UTF_8);
    }

    /**
     * Resolves a Scryfall path to a fully-qualified URL (using the http
     * client's configured base URL).
     */
    public String absoluteUrl(String path) {
        return httpClient.getBaseUrl() + path;
    }

    private ScryfallCard fetch(String path) {
        String fullUrl = absoluteUrl(path);
        try {
            String body = httpClient.get(path);
            return gson.fromJson(body, ScryfallCard.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScryfallLookupException(fullUrl, "Scryfall request was interrupted", e);
        } catch (IOException e) {
            throw new ScryfallLookupException(fullUrl, e.getMessage(), e);
        }
    }
}
