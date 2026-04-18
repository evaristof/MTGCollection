package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSetListResponse;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Service responsible for listing Magic: The Gathering sets from the Scryfall API.
 */
@Service
public class SetService {

    private final ScryfallHttpClient httpClient;
    private final Gson gson;

    public SetService(ScryfallHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /**
     * Calls GET {base}/sets and returns the list of {@link ScryfallSet} contained
     * inside the "data" field of the Scryfall response.
     *
     * @return list of Scryfall sets (never {@code null}, may be empty)
     */
    public List<ScryfallSet> listAllSets() {
        try {
            String body = httpClient.get("/sets");
            ScryfallSetListResponse response = gson.fromJson(body, ScryfallSetListResponse.class);
            if (response == null || response.getData() == null) {
                return Collections.emptyList();
            }
            return response.getData();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to list sets from Scryfall", e);
        }
    }
}
