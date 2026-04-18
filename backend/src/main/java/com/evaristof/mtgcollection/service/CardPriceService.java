package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Service responsible for fetching card prices from Scryfall, exposing two
 * lookups: by (card name + set code) and by (set code + collector number).
 *
 * <p>All prices returned are USD. When {@code foil == true} the foil price
 * ({@code usd_foil}) is returned; otherwise the regular USD price ({@code usd})
 * is returned. If the requested price is not available, {@code null} is returned.</p>
 */
@Service
public class CardPriceService {

    private final ScryfallHttpClient httpClient;
    private final Gson gson;

    public CardPriceService(ScryfallHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /**
     * Fetches the USD price of a card given its exact name and set code.
     *
     * @param cardName the exact card name
     * @param setCode  the three-to-five letter set code
     * @param foil     when {@code true}, returns the foil price; otherwise the non-foil price
     * @return the price in USD, or {@code null} if not available
     */
    public BigDecimal getPriceByNameAndSet(String cardName, String setCode, boolean foil) {
        if (cardName == null || cardName.isBlank()) {
            throw new IllegalArgumentException("cardName must not be blank");
        }
        if (setCode == null || setCode.isBlank()) {
            throw new IllegalArgumentException("setCode must not be blank");
        }

        String url = "/cards/named?exact=" + URLEncoder.encode(cardName, StandardCharsets.UTF_8)
                + "&set=" + URLEncoder.encode(setCode, StandardCharsets.UTF_8);
        return fetchPrice(url, foil);
    }

    /**
     * Fetches the USD price of a card given its set code and collector number.
     *
     * @param setCode      the set code (e.g. "neo")
     * @param cardNumber   the collector number within the set (e.g. "123")
     * @param foil         when {@code true}, returns the foil price; otherwise the non-foil price
     * @return the price in USD, or {@code null} if not available
     */
    public BigDecimal getPriceBySetAndNumber(String setCode, String cardNumber, boolean foil) {
        if (setCode == null || setCode.isBlank()) {
            throw new IllegalArgumentException("setCode must not be blank");
        }
        if (cardNumber == null || cardNumber.isBlank()) {
            throw new IllegalArgumentException("cardNumber must not be blank");
        }

        String url = "/cards/" + URLEncoder.encode(setCode, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(cardNumber, StandardCharsets.UTF_8);
        return fetchPrice(url, foil);
    }

    private BigDecimal fetchPrice(String path, boolean foil) {
        try {
            String body = httpClient.get(path);
            ScryfallCard card = gson.fromJson(body, ScryfallCard.class);
            if (card == null || card.getPrices() == null) {
                return null;
            }
            ScryfallPrices prices = card.getPrices();
            String raw = foil ? prices.getUsdFoil() : prices.getUsd();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return new BigDecimal(raw);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to fetch card price from Scryfall: " + path, e);
        }
    }
}
