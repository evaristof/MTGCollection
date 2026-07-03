package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CardNameCatalogServiceTest {

    @Test
    void refresh_populatesCacheFromCatalogEndpoint() throws Exception {
        ScryfallHttpClient client = mock(ScryfallHttpClient.class);
        when(client.get(anyString())).thenReturn(
                "{\"data\":[\"Lightning Bolt\",\"Yawgmoth's Bargain\",\"Necropotence\"]}");
        CardNameCatalogService service = new CardNameCatalogService(client, new Gson());

        service.refresh();

        assertThat(service.allNames()).containsExactly("Lightning Bolt", "Yawgmoth's Bargain", "Necropotence");
    }

    @Test
    void refresh_keepsPreviousCacheWhenFetchFails() throws Exception {
        ScryfallHttpClient client = mock(ScryfallHttpClient.class);
        when(client.get(anyString()))
                .thenReturn("{\"data\":[\"Lightning Bolt\"]}")
                .thenThrow(new RuntimeException("network down"));
        CardNameCatalogService service = new CardNameCatalogService(client, new Gson());
        service.refresh();

        service.refresh();

        assertThat(service.allNames()).containsExactly("Lightning Bolt");
    }

    @Test
    void fuzzyMatch_correctsOcrNoise() throws Exception {
        ScryfallHttpClient client = mock(ScryfallHttpClient.class);
        when(client.get(anyString())).thenReturn(
                "{\"data\":[\"Lightning Bolt\",\"Yawgmoth's Bargain\",\"Necropotence\"]}");
        CardNameCatalogService service = new CardNameCatalogService(client, new Gson());
        service.refresh();

        List<CardNameCatalogService.NameMatch> matches = service.fuzzyMatch("Yawgmoths Bargaln", 1);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Yawgmoth's Bargain");
        assertThat(matches.get(0).score()).isGreaterThan(80);
    }

    @Test
    void fuzzyMatch_emptyForBlankText() throws Exception {
        ScryfallHttpClient client = mock(ScryfallHttpClient.class);
        when(client.get(anyString())).thenReturn("{\"data\":[\"Lightning Bolt\"]}");
        CardNameCatalogService service = new CardNameCatalogService(client, new Gson());
        service.refresh();

        assertThat(service.fuzzyMatch("", 3)).isEmpty();
        assertThat(service.fuzzyMatch(null, 3)).isEmpty();
    }
}
