package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.repository.LocationRepository;
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
 * autocomplete on "Cadastro Cartas" and backs the standalone "Cadastro de
 * Localização" screen.
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationRepository repository;

    public LocationController(LocationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Location> list() {
        return repository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Location> getOne(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody LocationRequest req) {
        String name = req.name().trim();
        if (repository.existsByNameIgnoreCase(name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Já existe uma localização com esse nome"));
        }
        Location saved = repository.save(new Location(name, blankToNull(req.description())));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") Long id, @Valid @RequestBody LocationRequest req) {
        Location existing = repository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        String name = req.name().trim();
        Optional<Location> dup = repository.findByNameIgnoreCase(name);
        if (dup.isPresent() && !dup.get().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Já existe uma localização com esse nome"));
        }
        existing.setName(name);
        existing.setDescription(blankToNull(req.description()));
        return ResponseEntity.ok(repository.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** JSON body for create/update. */
    public record LocationRequest(
            @NotBlank String name,
            String description) {
    }
}
