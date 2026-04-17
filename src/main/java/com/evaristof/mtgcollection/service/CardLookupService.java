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

        String path = "/cards/named?exact=" + URLEncoder.encode(cardName, StandardCharsets.UTF_8)
                + "&set=" + URLEncoder.encode(setCode, StandardCharsets.UTF_8);
        try {
            String body = httpClient.get(path);
            return gson.fromJson(body, ScryfallCard.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scryfall request was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch card from Scryfall", e);
        }
    }
}
