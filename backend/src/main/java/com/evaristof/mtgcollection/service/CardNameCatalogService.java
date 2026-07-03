package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Caches the full list of distinct Magic card names from Scryfall's
 * {@code /catalog/card-names} endpoint and fuzzy-matches OCR'd title text
 * against it, correcting the inevitable OCR misreads (0/O, 1/l, missing
 * punctuation, etc.) the way a human proofreading the scan would.
 */
@Service
public class CardNameCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CardNameCatalogService.class);
    private static final String CATALOG_PATH = "/catalog/card-names";

    private final ScryfallHttpClient scryfallClient;
    private final Gson gson;
    private volatile List<String> cachedNames = List.of();

    public CardNameCatalogService(ScryfallHttpClient scryfallClient, Gson gson) {
        this.scryfallClient = scryfallClient;
        this.gson = gson;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    /**
     * Re-fetches the name catalog from Scryfall. Safe to call if the initial
     * startup fetch failed (e.g. no network yet) — leaves the previous cache
     * in place on failure so fuzzy matching degrades gracefully rather than
     * going empty.
     */
    public synchronized void refresh() {
        try {
            String json = scryfallClient.get(CATALOG_PATH);
            Map<String, Object> response = gson.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) response.get("data");
            if (names != null && !names.isEmpty()) {
                cachedNames = List.copyOf(names);
                log.info("Loaded {} card names from Scryfall catalog", cachedNames.size());
            }
        } catch (Exception e) {
            log.warn("Could not load Scryfall card-name catalog — fuzzy name matching "
                    + "unavailable until the next successful refresh: {}", e.getMessage());
        }
    }

    public List<String> allNames() {
        return cachedNames;
    }

    public record NameMatch(String name, int score) {}

    /**
     * Returns up to {@code limit} card names ranked by similarity to the given
     * OCR text, highest score first. Empty if the catalog hasn't loaded yet or
     * the OCR text is blank/unusable.
     */
    public List<NameMatch> fuzzyMatch(String ocrText, int limit) {
        if (cachedNames.isEmpty() || ocrText == null) {
            return List.of();
        }
        String cleaned = cleanOcrText(ocrText);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return FuzzySearch.extractTop(cleaned, cachedNames, limit).stream()
                .map(r -> new NameMatch(r.getString(), r.getScore()))
                .toList();
    }

    private String cleanOcrText(String text) {
        return text.replaceAll("[^\\p{L}\\p{N} ,'-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
