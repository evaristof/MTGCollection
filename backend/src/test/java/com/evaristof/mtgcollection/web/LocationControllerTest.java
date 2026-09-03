package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.service.LocationService;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private LocationService service;

    private static Location sample() {
        Location l = new Location("Caixa 3", "Cartas comuns");
        l.setId(1L);
        return l;
    }

    @Test
    void list_returnsAllOrderedByName() throws Exception {
        when(service.listAll()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Caixa 3")))
                .andExpect(jsonPath("$[0].description", is("Cartas comuns")));
    }

    @Test
    void create_savesAndReturns201() throws Exception {
        when(service.findByName("Caixa 3")).thenReturn(Optional.empty());
        when(service.create(eq("Caixa 3"), any())).thenReturn(sample());

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
        when(service.findByName("Caixa 3")).thenReturn(Optional.of(sample()));

        String body = objectMapper.writeValueAsString(Map.of("name", "Caixa 3"));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
        verify(service, never()).create(any(), any());
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
        when(service.findById(1L)).thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of("name", "Caixa 4"));

        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_returns409WhenRenamingToAnExistingName() throws Exception {
        Location other = new Location("Caixa 4", null);
        other.setId(2L);
        when(service.findById(1L)).thenReturn(Optional.of(sample()));
        when(service.findByName("Caixa 4")).thenReturn(Optional.of(other));

        String body = objectMapper.writeValueAsString(Map.of("name", "Caixa 4"));

        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void update_appliesFields() throws Exception {
        Location existing = sample();
        Location renamed = new Location("Caixa 3 renomeada", "nova descrição");
        renamed.setId(1L);
        when(service.findById(1L)).thenReturn(Optional.of(existing));
        when(service.findByName("Caixa 3 renomeada")).thenReturn(Optional.empty());
        when(service.update(existing, "Caixa 3 renomeada", "nova descrição")).thenReturn(renamed);

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
    void delete_returns204WhenNotInUse() throws Exception {
        when(service.exists(1L)).thenReturn(true);
        when(service.countCards(1L)).thenReturn(0L);

        mockMvc.perform(delete("/api/locations/1"))
                .andExpect(status().isNoContent());
        verify(service).delete(1L);
    }

    @Test
    void delete_returns409WhileCardsStillPointAtIt() throws Exception {
        when(service.exists(1L)).thenReturn(true);
        when(service.countCards(1L)).thenReturn(7L);

        mockMvc.perform(delete("/api/locations/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("7 carta(s)")));
        verify(service, never()).delete(1L);
    }

    @Test
    void delete_returns404WhenMissing() throws Exception {
        when(service.exists(1L)).thenReturn(false);

        mockMvc.perform(delete("/api/locations/1"))
                .andExpect(status().isNotFound());
    }
}
