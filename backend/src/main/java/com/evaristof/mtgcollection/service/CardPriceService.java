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
        try {
            String body = httpClient.get(path);
            ScryfallCard card = gson.fromJson(body, ScryfallCard.class);
            return CardPriceResolver.resolve(card, foil);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to fetch card price from Scryfall: " + path, e);
        }
    }
}
