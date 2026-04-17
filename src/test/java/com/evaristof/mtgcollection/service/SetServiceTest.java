package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetServiceTest {

    private ScryfallHttpClient httpClient;
    private SetService setService;

    @BeforeEach
    void setUp() {
        httpClient = mock(ScryfallHttpClient.class);
        setService = new SetService(httpClient, new Gson());
    }

    @Test
    void listAllSets_returnsSetsFromDataField() throws Exception {
        String body = """
                {
                  "object": "list",
                  "has_more": false,
                  "data": [
                    {
                      "code": "neo",
                      "name": "Kamigawa: Neon Dynasty",
                      "released_at": "2022-02-18",
                      "set_type": "expansion",
                      "card_count": 302,
                      "printed_size": 302,
                      "block_code": "NEO",
                      "block": "Kamigawa"
                    },
                    {
                      "code": "dmu",
                      "name": "Dominaria United",
                      "released_at": "2022-09-09",
                      "set_type": "expansion",
                      "card_count": 281
                    }
                  ]
                }
                """;
        when(httpClient.get(eq("/sets"))).thenReturn(body);

        List<ScryfallSet> sets = setService.listAllSets();

        assertThat(sets).hasSize(2);
        ScryfallSet first = sets.get(0);
        assertThat(first.getCode()).isEqualTo("neo");
        assertThat(first.getName()).isEqualTo("Kamigawa: Neon Dynasty");
        assertThat(first.getReleasedAt()).isEqualTo("2022-02-18");
        assertThat(first.getSetType()).isEqualTo("expansion");
        assertThat(first.getCardCount()).isEqualTo(302);
        assertThat(first.getPrintedSize()).isEqualTo(302);
        assertThat(first.getBlockCode()).isEqualTo("NEO");
        assertThat(first.getBlock()).isEqualTo("Kamigawa");

        ScryfallSet second = sets.get(1);
        assertThat(second.getCode()).isEqualTo("dmu");
        assertThat(second.getBlockCode()).isNull();
        assertThat(second.getBlock()).isNull();
        assertThat(second.getPrintedSize()).isNull();
    }

    @Test
    void listAllSets_returnsEmptyWhenDataMissing() throws Exception {
        when(httpClient.get(eq("/sets"))).thenReturn("{\"object\":\"list\"}");

        List<ScryfallSet> sets = setService.listAllSets();

        assertThat(sets).isEmpty();
    }

    @Test
    void listAllSets_wrapsIoExceptions() throws Exception {
        when(httpClient.get(eq("/sets"))).thenThrow(new java.io.IOException("boom"));

        assertThatThrownBy(() -> setService.listAllSets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to list sets");
    }
}
