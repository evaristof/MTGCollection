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
import static org.mockito.Mockito.times;

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
    void getCardByNameAndSet_httpFailureWrappedAsScryfallLookupException() throws Exception {
        when(httpClient.get(anyString())).thenThrow(new java.io.IOException("boom"));

        assertThatThrownBy(() -> service.getCardByNameAndSet("x", "neo"))
                .isInstanceOf(ScryfallLookupException.class)
                .hasMessageContaining("boom");
    }

    // --- foil-aware overloads: starred parallel-foil print (old core sets) ---

    private static String cardJson(String set, String number, String usd, String usdFoil) {
        return "{"
                + "\"object\":\"card\","
                + "\"name\":\"City of Brass\","
                + "\"set\":\"" + set + "\","
                + "\"collector_number\":\"" + number + "\","
                + "\"prices\":{"
                + (usd == null ? "\"usd\":null" : "\"usd\":\"" + usd + "\"")
                + ","
                + (usdFoil == null ? "\"usd_foil\":null" : "\"usd_foil\":\"" + usdFoil + "\"")
                + "}}";
    }

    @Test
    void getCardByNameAndSet_foil_fallsBackToStarredNumber_whenPlainPrintHasNoFoilPrice() throws Exception {
        // 7ED City of Brass: #327 is the nonfoil print (no usd_foil at all);
        // the foil is a separate print, #327★, which does have one.
        when(httpClient.get("/cards/named?exact=City+of+Brass&set=7ed"))
                .thenReturn(cardJson("7ed", "327", "0.50", null));
        when(httpClient.get("/cards/7ed/327%E2%98%85"))
                .thenReturn(cardJson("7ed", "327★", null, "12.00"));

        ScryfallCard card = service.getCardByNameAndSet("City of Brass", "7ed", true);

        assertThat(card.getCollectorNumber()).isEqualTo("327★");
        assertThat(card.getPrices().getUsdFoil()).isEqualTo("12.00");
    }

    @Test
    void getCardBySetAndNumber_foil_fallsBackToStarredNumber_whenPlainPrintHasNoFoilPrice() throws Exception {
        when(httpClient.get("/cards/7ed/327"))
                .thenReturn(cardJson("7ed", "327", "0.50", null));
        when(httpClient.get("/cards/7ed/327%E2%98%85"))
                .thenReturn(cardJson("7ed", "327★", null, "12.00"));

        ScryfallCard card = service.getCardBySetAndNumber("7ed", "327", true);

        assertThat(card.getCollectorNumber()).isEqualTo("327★");
        assertThat(card.getPrices().getUsdFoil()).isEqualTo("12.00");
    }

    @Test
    void getCardByNameAndSet_foil_keepsOriginalPrint_whenItAlreadyHasAFoilPrice() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("2x2", "117", "1.23", "5.67"));

        ScryfallCard card = service.getCardByNameAndSet("Lightning Bolt", "2x2", true);

        assertThat(card.getCollectorNumber()).isEqualTo("117");
        // only the one call — no starred fallback attempted
        verify(httpClient, times(1)).get(anyString());
    }

    @Test
    void getCardByNameAndSet_foil_keepsOriginalPrint_whenNoStarredPrintExists() throws Exception {
        // Most cards simply have no parallel-foil print — Scryfall 404s on
        // the starred number, and that must not blow up the whole lookup.
        when(httpClient.get("/cards/named?exact=Sol+Ring&set=lea"))
                .thenReturn(cardJson("lea", "161", "150.00", null));
        when(httpClient.get("/cards/lea/161%E2%98%85"))
                .thenThrow(new java.io.IOException("Scryfall request failed: 404 for .../lea/161★"));

        ScryfallCard card = service.getCardByNameAndSet("Sol Ring", "lea", true);

        assertThat(card.getCollectorNumber()).isEqualTo("161");
        assertThat(card.getPrices().getUsdFoil()).isNull();
    }

    @Test
    void getCardByNameAndSet_nonFoil_neverTriesStarredFallback() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("7ed", "327", "0.50", null));

        ScryfallCard card = service.getCardByNameAndSet("City of Brass", "7ed", false);

        assertThat(card.getCollectorNumber()).isEqualTo("327");
        verify(httpClient, times(1)).get(anyString());
    }

    @Test
    void getCardBySetAndNumber_foil_doesNotRetry_whenNumberIsAlreadyStarred() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("7ed", "327★", null, null));

        ScryfallCard card = service.getCardBySetAndNumber("7ed", "327★", true);

        assertThat(card.getCollectorNumber()).isEqualTo("327★");
        verify(httpClient, times(1)).get(anyString());
    }
}
