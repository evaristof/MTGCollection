package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardBatchLookupServiceTest {

    private ScryfallHttpClient httpClient;
    private CardBatchLookupService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(ScryfallHttpClient.class);
        // Match the production bean: Gson with serializeNulls() enabled.
        Gson gson = new GsonBuilder().serializeNulls().create();
        service = new CardBatchLookupService(httpClient, gson);
    }

    @Test
    void postBatch_doesNotSerializeNullIdentifierFields() throws Exception {
        when(httpClient.postJson(eq("/cards/collection"), ArgumentCaptor.forClass(String.class).capture()))
                .thenReturn("{\"object\":\"list\",\"data\":[],\"not_found\":[]}");

        List<ScryfallCardIdentifier> identifiers = new ArrayList<>();
        identifiers.add(ScryfallCardIdentifier.bySetAndNumber("zen", "180"));
        identifiers.add(ScryfallCardIdentifier.byNameAndSet("Lightning Bolt", "m10"));

        service.getCardsBatch(identifiers, 0L, new ArrayList<>());

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).postJson(eq("/cards/collection"), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        // Must NOT contain `"name": null` or `"collector_number": null` — Scryfall
        // interprets the mere presence of a key as "use this identifier shape"
        // and rejects such requests with 400.
        assertThat(body).doesNotContain("null");
        assertThat(body).contains("\"set\":\"zen\"");
        assertThat(body).contains("\"collector_number\":\"180\"");
        assertThat(body).contains("\"name\":\"Lightning Bolt\"");
        assertThat(body).contains("\"set\":\"m10\"");
    }

    @Test
    void getCardsBatch_emptyInputReturnsEmptyListWithoutHttpCall() throws Exception {
        List<com.evaristof.mtgcollection.scryfall.dto.ScryfallCard> result =
                service.getCardsBatch(List.of(), 0L, new ArrayList<>());

        assertThat(result).isEmpty();
        verify(httpClient, org.mockito.Mockito.never()).postJson(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
