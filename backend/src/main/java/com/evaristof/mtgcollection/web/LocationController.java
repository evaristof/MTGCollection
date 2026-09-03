package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.service.LocationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD endpoints for the {@code LOCATION} table — the catalog of physical
 * storage locations (boxes, binders, shelves…) that feeds the location
 * autocomplete on "Cadastro Cartas"/"Cartas" and backs the standalone
 * "Cadastro de Localização" screen.
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService service;

    public LocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Location> list() {
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Location> getOne(@PathVariable("id") Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody LocationRequest req) {
        String name = req.name().trim();
        if (service.findByName(name).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Já existe uma localização com esse nome"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(name, req.description()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") Long id, @Valid @RequestBody LocationRequest req) {
        Location existing = service.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        String name = req.name().trim();
        Optional<Location> dup = service.findByName(name);
        if (dup.isPresent() && !dup.get().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Já existe uma localização com esse nome"));
        }
        return ResponseEntity.ok(service.update(existing, name, req.description()));
    }

    /**
     * Removes a location. Refused with {@code 409} while cards still point at
     * it — the FK would reject the delete anyway, and the count tells the user
     * how many cards to move first.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        long inUse = service.countCards(id);
        if (inUse > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Localização em uso por " + inUse
                            + " carta(s). Mova essas cartas antes de remover."));
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** JSON body for create/update. */
    public record LocationRequest(
            @NotBlank String name,
            String description) {
    }
}
