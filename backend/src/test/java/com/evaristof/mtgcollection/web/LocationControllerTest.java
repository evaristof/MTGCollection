package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.repository.LocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

@WebMvcTest(LocationController.class)
class LocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LocationRepository repository;

    private static Location sample() {
        Location l = new Location("Caixa 3", "Cartas comuns");
        l.setId(1L);
        return l;
    }

    @Test
    void list_returnsAllOrderedByName() throws Exception {
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Caixa 3")));
    }

    @Test
    void create_savesAndReturns201() throws Exception {
        when(repository.existsByNameIgnoreCase("Caixa 3")).thenReturn(false);
        when(repository.save(any(Location.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Caixa 3",
                "description", "Cartas comuns"));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Caixa 3")));
    }

    @Test
    void create_returns409WhenNameAlreadyExists() throws Exception {
        when(repository.existsByNameIgnoreCase("Caixa 3")).thenReturn(true);

        String body = objectMapper.writeValueAsString(Map.of("name", "Caixa 3"));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void create_returns400OnBlankName() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", ""));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_returns404WhenMissing() throws Exception {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of("name", "Caixa 4"));

        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_returns409WhenRenamingToAnExistingName() throws Exception {
        Location existing = sample();
        Location other = new Location("Caixa 4", null);
        other.setId(2L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.findByNameIgnoreCase("Caixa 4")).thenReturn(Optional.of(other));

        String body = objectMapper.writeValueAsString(Map.of("name", "Caixa 4"));

        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void update_appliesFields() throws Exception {
        Location existing = sample();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.findByNameIgnoreCase("Caixa 3 renomeada")).thenReturn(Optional.empty());
        when(repository.save(any(Location.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Caixa 3 renomeada",
                "description", "nova descrição"));

        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Caixa 3 renomeada")))
                .andExpect(jsonPath("$.description", is("nova descrição")));
    }

    @Test
    void delete_returns204WhenFound() throws Exception {
        when(repository.existsById(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/locations/1"))
                .andExpect(status().isNoContent());
        verify(repository).deleteById(1L);
    }

    @Test
    void delete_returns404WhenMissing() throws Exception {
        when(repository.existsById(1L)).thenReturn(false);

        mockMvc.perform(delete("/api/locations/1"))
                .andExpect(status().isNotFound());
    }
}
