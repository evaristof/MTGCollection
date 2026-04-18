package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallBatchResponse;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up many Scryfall cards in one shot via {@code POST /cards/collection},
 * which accepts up to 75 identifiers per request and returns the matching
 * card objects plus a list of identifiers that could not be resolved.
 *
 * <p>This service replaces per-row lookups during spreadsheet import — for
 * a ~2 000-card collection it cuts the number of HTTP requests from ~2 000
 * down to ~27, staying comfortably inside Scryfall's 10 req/s limit even
 * with a small throttle between batches.</p>
 */
@Service
public class CardBatchLookupService {

    private static final Logger log = LoggerFactory.getLogger(CardBatchLookupService.class);

    /** Scryfall caps /cards/collection requests at 75 identifiers each. */
    public static final int MAX_BATCH_SIZE = 75;

    private final ScryfallHttpClient httpClient;
    private final Gson gson;

    public CardBatchLookupService(ScryfallHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /**
     * Splits {@code identifiers} into batches of {@link #MAX_BATCH_SIZE} and
     * calls {@code POST /cards/collection} for each. Between batches we
     * sleep {@code throttleMs} milliseconds to stay friendly to Scryfall's
     * rate limiter.
     *
     * <p>The return value is the list of cards that matched, in the order
     * Scryfall returned them. Identifiers that did not match are collected
     * into {@code notFoundSink} (when non-{@code null}) so the caller can
     * surface them to the user.</p>
     */
    public List<ScryfallCard> getCardsBatch(List<ScryfallCardIdentifier> identifiers,
                                            long throttleMs,
                                            List<ScryfallCardIdentifier> notFoundSink) {
        if (identifiers == null || identifiers.isEmpty()) return Collections.emptyList();

        List<ScryfallCard> all = new ArrayList<>(identifiers.size());
        int totalBatches = (identifiers.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        int batchIdx = 0;

        for (int i = 0; i < identifiers.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, identifiers.size());
            List<ScryfallCardIdentifier> batch = identifiers.subList(i, end);
            batchIdx++;
            log.debug("Scryfall batch {}/{} ({} identifiers)", batchIdx, totalBatches, batch.size());

            ScryfallBatchResponse response = postBatch(batch);
            if (response.getData() != null) {
                all.addAll(response.getData());
            }
            if (notFoundSink != null && response.getNotFound() != null) {
                notFoundSink.addAll(response.getNotFound());
            }

            if (batchIdx < totalBatches && throttleMs > 0) {
                try {
                    Thread.sleep(throttleMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ScryfallLookupException(
                            httpClient.getBaseUrl() + "/cards/collection",
                            "Interrompido durante throttle entre batches",
                            e);
                }
            }
        }

        return all;
    }

    private ScryfallBatchResponse postBatch(List<ScryfallCardIdentifier> batch) {
        Map<String, Object> body = new HashMap<>();
        body.put("identifiers", batch);
        String json = gson.toJson(body);
        String url = httpClient.getBaseUrl() + "/cards/collection";
        try {
            String responseBody = httpClient.postJson("/cards/collection", json);
            ScryfallBatchResponse parsed = gson.fromJson(responseBody, ScryfallBatchResponse.class);
            if (parsed == null) {
                throw new ScryfallLookupException(url, "Scryfall retornou corpo vazio", null);
            }
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScryfallLookupException(url, "Scryfall request interrompido", e);
        } catch (IOException e) {
            throw new ScryfallLookupException(url, e.getMessage(), e);
        }
    }
}
