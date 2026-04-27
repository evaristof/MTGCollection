package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.ScryfallHttpClient;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardPriceServiceTest {

    private ScryfallHttpClient httpClient;
    private CardPriceService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(ScryfallHttpClient.class);
        service = new CardPriceService(httpClient, new Gson());
    }

    private static String cardJson(String usd, String usdFoil) {
        return "{"
                + "\"object\":\"card\","
                + "\"id\":\"abc\","
                + "\"name\":\"Lightning Bolt\","
                + "\"set\":\"2x2\","
                + "\"collector_number\":\"117\","
                + "\"prices\":{"
                + (usd == null ? "\"usd\":null" : "\"usd\":\"" + usd + "\"")
                + ","
                + (usdFoil == null ? "\"usd_foil\":null" : "\"usd_foil\":\"" + usdFoil + "\"")
                + "}}";
    }

    @Test
    void getPriceByNameAndSet_nonFoil_returnsUsdPrice() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("1.23", "5.67"));

        BigDecimal price = service.getPriceByNameAndSet("Lightning Bolt", "2x2", false);

        assertThat(price).isEqualByComparingTo("1.23");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).get(captor.capture());
        assertThat(captor.getValue())
                .startsWith("/cards/named?exact=")
                .contains("Lightning+Bolt")
                .contains("&set=2x2");
    }

    @Test
    void getPriceByNameAndSet_foil_returnsUsdFoilPrice() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("1.23", "5.67"));

        BigDecimal price = service.getPriceByNameAndSet("Lightning Bolt", "2x2", true);

        assertThat(price).isEqualByComparingTo("5.67");
    }

    @Test
    void getPriceByNameAndSet_returnsNullWhenPriceMissing() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson(null, "5.67"));

        BigDecimal price = service.getPriceByNameAndSet("Lightning Bolt", "2x2", false);

        assertThat(price).isNull();
    }

    @Test
    void getPriceByNameAndSet_returnsNullWhenPricesObjectMissing() throws Exception {
        when(httpClient.get(anyString())).thenReturn("{\"object\":\"card\",\"name\":\"x\"}");

        BigDecimal price = service.getPriceByNameAndSet("x", "abc", false);

        assertThat(price).isNull();
    }

    @Test
    void getPriceBySetAndNumber_buildsCorrectUrlAndReturnsPrice() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("0.50", "2.00"));

        BigDecimal price = service.getPriceBySetAndNumber("neo", "123", false);

        assertThat(price).isEqualByComparingTo("0.50");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).get(captor.capture());
        assertThat(captor.getValue()).isEqualTo("/cards/neo/123");
    }

    @Test
    void getPriceBySetAndNumber_foilReturnsFoilPrice() throws Exception {
        when(httpClient.get(anyString())).thenReturn(cardJson("0.50", "2.00"));

        BigDecimal price = service.getPriceBySetAndNumber("neo", "123", true);

        assertThat(price).isEqualByComparingTo("2.00");
    }

    @Test
    void getPriceByNameAndSet_blankInputsThrow() {
        assertThatThrownBy(() -> service.getPriceByNameAndSet("", "neo", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getPriceByNameAndSet("x", " ", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPriceBySetAndNumber_blankInputsThrow() {
        assertThatThrownBy(() -> service.getPriceBySetAndNumber(" ", "1", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getPriceBySetAndNumber("neo", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpFailureWrappedAsIllegalState() throws Exception {
        when(httpClient.get(anyString())).thenThrow(new java.io.IOException("nope"));

        assertThatThrownBy(() -> service.getPriceByNameAndSet("x", "neo", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fetch card price");
    }
}
