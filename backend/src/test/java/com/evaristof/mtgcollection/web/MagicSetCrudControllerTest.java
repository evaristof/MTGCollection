package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MagicSetCrudController.class)
class MagicSetCrudControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MagicSetRepository repository;

    private static MagicSet sample() {
        return new MagicSet("neo", "Kamigawa: Neon Dynasty",
                LocalDate.of(2022, 2, 18), "expansion",
                302, 302, "NEO", "Kamigawa");
    }

    @Test
    void list_returnsAllSetsFromDb() throws Exception {
        when(repository.findAll()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/sets/db"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].set_code", is("neo")))
                .andExpect(jsonPath("$[0].set_name", is("Kamigawa: Neon Dynasty")))
                .andExpect(jsonPath("$[0].release_date", is("2022-02-18")));
    }

    @Test
    void getOne_returns404WhenMissing() throws Exception {
        when(repository.findById("xxx")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sets/db/xxx"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOne_returnsEntity() throws Exception {
        when(repository.findById("neo")).thenReturn(Optional.of(sample()));

        mockMvc.perform(get("/api/sets/db/neo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set_code", is("neo")));
    }

    @Test
    void create_savesAndReturns201() throws Exception {
        when(repository.existsById("neo")).thenReturn(false);
        when(repository.save(any(MagicSet.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = objectMapper.writeValueAsString(Map.of(
                "set_code", "neo",
                "set_name", "Kamigawa: Neon Dynasty",
                "release_date", "2022-02-18",
                "set_type", "expansion",
                "card_count", 302,
                "printed_size", 302,
                "block_code", "NEO",
                "block_name", "Kamigawa"));

        mockMvc.perform(post("/api/sets/db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.set_code", is("neo")))
                .andExpect(jsonPath("$.block_name", is("Kamigawa")));
    }

    @Test
    void create_returns409WhenAlreadyExists() throws Exception {
        when(repository.existsById("neo")).thenReturn(true);

        String body = objectMapper.writeValueAsString(Map.of(
                "set_code", "neo",
                "set_name", "Kamigawa: Neon Dynasty"));

        mockMvc.perform(post("/api/sets/db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void create_returns400OnInvalidBody() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "set_code", "",
                "set_name", ""));

        mockMvc.perform(post("/api/sets/db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_returns404WhenMissing() throws Exception {
        when(repository.findById("neo")).thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of(
                "set_code", "neo",
                "set_name", "New Name"));

        mockMvc.perform(put("/api/sets/db/neo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_returns400WhenPathAndBodyCodeDiffer() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "set_code", "other",
                "set_name", "x"));

        mockMvc.perform(put("/api/sets/db/neo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_appliesFields() throws Exception {
        MagicSet existing = sample();
        when(repository.findById("neo")).thenReturn(Optional.of(existing));
        when(repository.save(any(MagicSet.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = objectMapper.writeValueAsString(Map.of(
                "set_code", "neo",
                "set_name", "Renamed",
                "set_type", "core",
                "card_count", 500,
                "block_code", "XYZ",
                "block_name", "New Block"));

        mockMvc.perform(put("/api/sets/db/neo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set_name", is("Renamed")))
                .andExpect(jsonPath("$.set_type", is("core")))
                .andExpect(jsonPath("$.card_count", is(500)))
                .andExpect(jsonPath("$.block_name", is("New Block")));
    }

    @Test
    void delete_returns204WhenFound() throws Exception {
        when(repository.existsById("neo")).thenReturn(true);

        mockMvc.perform(delete("/api/sets/db/neo"))
                .andExpect(status().isNoContent());
        verify(repository).deleteById("neo");
    }

    @Test
    void delete_returns404WhenMissing() throws Exception {
        when(repository.existsById("neo")).thenReturn(false);

        mockMvc.perform(delete("/api/sets/db/neo"))
                .andExpect(status().isNotFound());
    }
}
