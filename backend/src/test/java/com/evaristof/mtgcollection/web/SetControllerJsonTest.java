package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import com.evaristof.mtgcollection.service.SetImageService;
import com.evaristof.mtgcollection.service.SetPersistenceService;
import com.evaristof.mtgcollection.service.SetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the Scryfall-derived DTOs are serialized with snake_case field
 * names so that the HTTP API contract matches the Scryfall payload shape.
 *
 * <p>Jackson (the default JSON serializer in Spring MVC) ignores Gson's
 * {@code @SerializedName} annotations, so we rely on the global
 * {@code spring.jackson.property-naming-strategy=SNAKE_CASE} setting. This
 * test guards against that setting accidentally being removed.</p>
 */
@WebMvcTest(SetController.class)
class SetControllerJsonTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SetService setService;

    @MockBean
    private SetPersistenceService setPersistenceService;

    @MockBean
    private SetImageService setImageService;

    @Test
    void listSets_serializesWithSnakeCaseFieldNames() throws Exception {
        ScryfallSet set = new ScryfallSet();
        set.setCode("neo");
        set.setName("Kamigawa: Neon Dynasty");
        set.setReleasedAt("2022-02-18");
        set.setSetType("expansion");
        set.setCardCount(302);
        set.setPrintedSize(302);
        set.setBlockCode("NEO");
        set.setBlock("Kamigawa");
        when(setService.listAllSets()).thenReturn(List.of(set));

        mockMvc.perform(get("/api/sets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code", is("neo")))
                .andExpect(jsonPath("$[0].name", is("Kamigawa: Neon Dynasty")))
                .andExpect(jsonPath("$[0].released_at", is("2022-02-18")))
                .andExpect(jsonPath("$[0].set_type", is("expansion")))
                .andExpect(jsonPath("$[0].card_count", is(302)))
                .andExpect(jsonPath("$[0].printed_size", is(302)))
                .andExpect(jsonPath("$[0].block_code", is("NEO")))
                .andExpect(jsonPath("$[0].block", is("Kamigawa")));
    }
}
