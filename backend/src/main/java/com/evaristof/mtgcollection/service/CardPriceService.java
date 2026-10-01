package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Service responsible for fetching card prices from Scryfall, exposing two
 * lookups: by (card name + set code) and by (set code + collector number).
 *
 * <p>Preço e moeda saem de {@link CardPriceResolver}: dólar por padrão e, para
 * foil sem {@code usd_foil}, o valor em euro ({@code eur_foil}) — por isso o
 * retorno carrega a moeda em vez de assumir USD.</p>
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
     * Preço de uma carta pelo nome exato e código do set.
     *
     * @param cardName the exact card name
     * @param setCode  the three-to-five letter set code
     * @param foil     when {@code true}, returns the foil price; otherwise the non-foil price
     * @return preço e moeda; {@code price} nulo quando o Scryfall não tem preço
     */
    public CardPriceResolver.Resolved getPriceByNameAndSet(String cardName, String setCode, boolean foil) {
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
     * Preço de uma carta pelo código do set e número do coletor.
     *
     * @param setCode      the set code (e.g. "neo")
     * @param cardNumber   the collector number within the set (e.g. "123")
     * @param foil         when {@code true}, returns the foil price; otherwise the non-foil price
     * @return preço e moeda; {@code price} nulo quando o Scryfall não tem preço
     */
    public CardPriceResolver.Resolved getPriceBySetAndNumber(String setCode, String cardNumber, boolean foil) {
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

    private CardPriceResolver.Resolved fetchPrice(String path, boolean foil) {
        ScryfallCard card = fetchCard(path);
        CardPriceResolver.Resolved resolved = CardPriceResolver.resolve(card, foil);
        if (foil && !resolved.hasPrice()) {
            ScryfallCard starred = fetchStarredFoilFallback(card);
            if (starred != null) {
                CardPriceResolver.Resolved starredResolved = CardPriceResolver.resolve(starred, true);
                if (starredResolved.hasPrice()) {
                    return starredResolved;
                }
            }
        }
        return resolved;
    }

    private ScryfallCard fetchCard(String path) {
        try {
            String body = httpClient.get(path);
            return gson.fromJson(body, ScryfallCard.class);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to fetch card price from Scryfall: " + path, e);
        }
    }

    /**
     * Old core sets (6th–10th Edition and a few others) print the foil
     * version of a card as a separate Scryfall print, with its own collector
     * number suffixed {@value CardPriceResolver#FOIL_STAR_SUFFIX} — e.g. City
     * of Brass is 7ED #327 (nonfoil) and 7ED #327★ (foil). The plain
     * name/number lookup above returns the nonfoil print for those sets,
     * which has no foil price at all. So when a foil price was requested and
     * came back empty, we try the starred collector number once; most cards
     * don't have a parallel-foil print, so a 404 here just means "no price",
     * same as before.
     */
    private ScryfallCard fetchStarredFoilFallback(ScryfallCard originalCard) {
        if (originalCard == null) {
            return null;
        }
        String number = originalCard.getCollectorNumber();
        String set = originalCard.getSet();
        if (number == null || number.isBlank() || set == null || set.isBlank()
                || number.endsWith(CardPriceResolver.FOIL_STAR_SUFFIX)) {
            return null;
        }
        String starredPath = "/cards/" + URLEncoder.encode(set, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(number + CardPriceResolver.FOIL_STAR_SUFFIX, StandardCharsets.UTF_8);
        try {
            return fetchCard(starredPath);
        } catch (IllegalStateException e) {
            // No parallel-foil print for this card/set — the common case.
            return null;
        }
    }
}
