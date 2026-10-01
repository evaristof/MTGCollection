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

    /**
     * Same as {@link #getCardByNameAndSet(String, String)}, but when
     * {@code foil} is {@code true} and the print that comes back has no foil
     * price at all, also tries the "starred" parallel-foil print for the same
     * set (see {@link #preferFoilPrintIfNeeded}).
     */
    public ScryfallCard getCardByNameAndSet(String cardName, String setCode, boolean foil) {
        return preferFoilPrintIfNeeded(getCardByNameAndSet(cardName, setCode), foil);
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

    /**
     * Same as {@link #getCardBySetAndNumber(String, String)}, but when
     * {@code foil} is {@code true} and the print that comes back has no foil
     * price at all, also tries the "starred" parallel-foil print for the same
     * set (see {@link #preferFoilPrintIfNeeded}).
     */
    public ScryfallCard getCardBySetAndNumber(String setCode, String collectorNumber, boolean foil) {
        return preferFoilPrintIfNeeded(getCardBySetAndNumber(setCode, collectorNumber), foil);
    }

    /**
     * Old core sets (6th–10th Edition and a few others) printed the foil
     * version of a card as a separate Scryfall print, with its own collector
     * number suffixed {@value CardPriceResolver#FOIL_STAR_SUFFIX} — e.g. City
     * of Brass is 7ED #327 (nonfoil) and 7ED #327★ (foil). The "plain" print
     * that {@link #getCardByNameAndSet(String, String)} /
     * {@link #getCardBySetAndNumber(String, String)} return for those sets
     * carries no foil price at all ({@code usd_foil}, {@code usd_etched} and
     * {@code eur_foil} are all {@code null} on it), even though Scryfall does
     * have a priced foil print under the starred number.
     *
     * <p>So when the caller wants a foil price and the print in hand doesn't
     * have one, we try that starred number once. Most cards don't have a
     * parallel-foil print at all — Scryfall then 404s on the starred number,
     * which we treat as "no such print" and just keep the original card.</p>
     */
    private ScryfallCard preferFoilPrintIfNeeded(ScryfallCard card, boolean foil) {
        if (!foil || card == null) {
            return card;
        }
        if (CardPriceResolver.resolve(card, true).hasPrice()) {
            return card;
        }
        String number = card.getCollectorNumber();
        String set = card.getSet();
        if (number == null || number.isBlank() || set == null || set.isBlank()
                || number.endsWith(CardPriceResolver.FOIL_STAR_SUFFIX)) {
            return card;
        }
        try {
            ScryfallCard starred = getCardBySetAndNumber(set, number + CardPriceResolver.FOIL_STAR_SUFFIX);
            return starred != null ? starred : card;
        } catch (ScryfallLookupException e) {
            // No parallel-foil print for this card/set — the common case.
            return card;
        }
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
