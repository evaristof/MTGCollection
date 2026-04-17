package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.service.CollectionCardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CollectionCardController.class)
class CollectionCardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CollectionCardService service;

    private static CollectionCard sampleEntity() {
        CollectionCard c = new CollectionCard();
        c.setId(1L);
        c.setCardName("Lightning Bolt");
        c.setSetCode("2x2");
        c.setCardNumber("117");
        c.setFoil(true);
        c.setCardType("Instant");
        c.setLanguage("en");
        c.setQuantity(4);
        return c;
    }

    @Test
    void post_addsCardAndReturnsSnakeCaseJson() throws Exception {
        when(service.addCardToCollection(anyString(), anyString(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(sampleEntity());

        String body = objectMapper.writeValueAsString(Map.of(
                "card_name", "Lightning Bolt",
                "set_code", "2x2",
                "foil", true,
                "language", "en",
                "quantity", 4));

        mockMvc.perform(post("/api/collection/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.card_name", is("Lightning Bolt")))
                .andExpect(jsonPath("$.set_code", is("2x2")))
                .andExpect(jsonPath("$.card_number", is("117")))
                .andExpect(jsonPath("$.card_type", is("Instant")))
                .andExpect(jsonPath("$.foil", is(true)))
                .andExpect(jsonPath("$.language", is("en")))
                .andExpect(jsonPath("$.quantity", is(4)));

        verify(service).addCardToCollection("Lightning Bolt", "2x2", true, "en", 4);
    }

    @Test
    void post_rejectsInvalidBody() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "card_name", "",
                "set_code", "neo",
                "foil", false,
                "language", "en",
                "quantity", 0));

        mockMvc.perform(post("/api/collection/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_withoutSet_listsAll() throws Exception {
        when(service.listAll()).thenReturn(List.of(sampleEntity()));

        mockMvc.perform(get("/api/collection/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].card_name", is("Lightning Bolt")));
        verify(service).listAll();
    }

    @Test
    void get_withSet_listsBySet() throws Exception {
        when(service.listBySet(eq("2x2"))).thenReturn(List.of(sampleEntity()));

        mockMvc.perform(get("/api/collection/cards").param("set", "2x2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        verify(service).listBySet("2x2");
    }
}
