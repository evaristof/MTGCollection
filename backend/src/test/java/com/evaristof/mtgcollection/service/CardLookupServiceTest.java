package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardLookupServiceTest {

    private ScryfallHttpClient httpClient;
    private CardLookupService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(ScryfallHttpClient.class);
        service = new CardLookupService(httpClient, new Gson());
    }

    private static String sampleCardJson() {
        return "{"
                + "\"object\":\"card\","
                + "\"id\":\"abc-123\","
                + "\"name\":\"Lightning Bolt\","
                + "\"set\":\"2x2\","
                + "\"collector_number\":\"117\","
                + "\"type_line\":\"Instant\","
                + "\"lang\":\"en\","
                + "\"rarity\":\"common\","
                + "\"mana_cost\":\"{R}\","
                + "\"oracle_text\":\"Lightning Bolt deals 3 damage to any target.\","
                + "\"prices\":{\"usd\":\"1.23\",\"usd_foil\":\"5.67\"}"
                + "}";
    }

    @Test
    void getCardByNameAndSet_buildsCorrectUrlAndDeserializesResponse() throws Exception {
        when(httpClient.get(anyString())).thenReturn(sampleCardJson());

        ScryfallCard card = service.getCardByNameAndSet("Lightning Bolt", "2x2");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).get(captor.capture());
        assertThat(captor.getValue())
                .startsWith("/cards/named?exact=")
                .contains("Lightning+Bolt")
                .contains("&set=2x2");

        assertThat(card).isNotNull();
        assertThat(card.getName()).isEqualTo("Lightning Bolt");
        assertThat(card.getSet()).isEqualTo("2x2");
        assertThat(card.getCollectorNumber()).isEqualTo("117");
        assertThat(card.getTypeLine()).isEqualTo("Instant");
        assertThat(card.getLang()).isEqualTo("en");
        assertThat(card.getRarity()).isEqualTo("common");
        assertThat(card.getManaCost()).isEqualTo("{R}");
        assertThat(card.getOracleText()).contains("3 damage");
        assertThat(card.getPrices()).isNotNull();
        assertThat(card.getPrices().getUsd()).isEqualTo("1.23");
        assertThat(card.getPrices().getUsdFoil()).isEqualTo("5.67");
    }

    @Test
    void getCardByNameAndSet_blankInputsThrow() {
        assertThatThrownBy(() -> service.getCardByNameAndSet("", "neo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getCardByNameAndSet("x", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getCardByNameAndSet(null, "neo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getCardByNameAndSet_httpFailureWrappedAsIllegalState() throws Exception {
        when(httpClient.get(anyString())).thenThrow(new java.io.IOException("boom"));

        assertThatThrownBy(() -> service.getCardByNameAndSet("x", "neo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fetch card");
    }
}
