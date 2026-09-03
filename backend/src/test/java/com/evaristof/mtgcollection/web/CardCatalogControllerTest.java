package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CardCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CardCatalogController.class)
class CardCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CardCatalogService service;

    @Test
    void names_returnsList() throws Exception {
        when(service.allCardNames()).thenReturn(List.of("Lightning Bolt", "Sol Ring"));

        mockMvc.perform(get("/api/card-catalog/names"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]", is("Lightning Bolt")));
    }

    @Test
    void sets_returnsOptionsForName() throws Exception {
        when(service.setsForCardName("Sol Ring"))
                .thenReturn(List.of(new CardCatalogService.SetOption("cmr", "Commander Legends")));

        mockMvc.perform(get("/api/card-catalog/sets").param("name", "Sol Ring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].set_code", is("cmr")))
                .andExpect(jsonPath("$[0].set_name", is("Commander Legends")));
    }

    @Test
    void lookupNumber_found() throws Exception {
        when(service.lookupCardName("neo", "123")).thenReturn(Optional.of("Boseiju, Who Endures"));

        mockMvc.perform(get("/api/card-catalog/lookup-number").param("set", "neo").param("number", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.card_name", is("Boseiju, Who Endures")));
    }

    @Test
    void lookupNumber_notFound() throws Exception {
        when(service.lookupCardName("neo", "999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/card-catalog/lookup-number").param("set", "neo").param("number", "999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveNumber_found() throws Exception {
        when(service.resolveCollectorNumber("neo", "Boseiju, Who Endures")).thenReturn(Optional.of("123"));

        mockMvc.perform(get("/api/card-catalog/resolve-number").param("set", "neo").param("name", "Boseiju, Who Endures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collector_number", is("123")));
    }
}
