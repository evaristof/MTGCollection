package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import com.evaristof.mtgcollection.service.CardLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CardLookupController.class)
class CardLookupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CardLookupService service;

    @Test
    void byName_returnsSnakeCaseJson() throws Exception {
        ScryfallCard card = new ScryfallCard();
        card.setName("Lightning Bolt");
        card.setSet("2x2");
        card.setCollectorNumber("117");
        card.setTypeLine("Instant");
        card.setLang("en");
        card.setRarity("common");
        card.setManaCost("{R}");
        ScryfallPrices prices = new ScryfallPrices();
        prices.setUsd("1.23");
        prices.setUsdFoil("5.67");
        card.setPrices(prices);

        when(service.getCardByNameAndSet("Lightning Bolt", "2x2")).thenReturn(card);

        mockMvc.perform(get("/api/cards/by-name")
                        .param("name", "Lightning Bolt")
                        .param("set", "2x2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Lightning Bolt")))
                .andExpect(jsonPath("$.set", is("2x2")))
                .andExpect(jsonPath("$.collector_number", is("117")))
                .andExpect(jsonPath("$.type_line", is("Instant")))
                .andExpect(jsonPath("$.lang", is("en")))
                .andExpect(jsonPath("$.rarity", is("common")))
                .andExpect(jsonPath("$.mana_cost", is("{R}")))
                .andExpect(jsonPath("$.prices.usd", is("1.23")))
                .andExpect(jsonPath("$.prices.usd_foil", is("5.67")));
    }
}
