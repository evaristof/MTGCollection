package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
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

import java.time.LocalDate;
import java.util.List;

/**
 * CRUD endpoints for the locally persisted {@code MAGIC_SET} table.
 *
 * <p>Separate from {@link SetController}, which exposes the live Scryfall
 * listing and the sync action. This controller is meant to back the
 * management UI for sets (manual create/update/delete on top of what
 * was synced).</p>
 */
@RestController
@RequestMapping("/api/sets/db")
public class MagicSetCrudController {

    private final MagicSetRepository repository;

    public MagicSetCrudController(MagicSetRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<MagicSet> list() {
        return repository.findAll();
    }

    @GetMapping("/{code}")
    public ResponseEntity<MagicSet> getOne(@PathVariable("code") String code) {
        return repository.findById(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<MagicSet> create(@Valid @RequestBody SetRequest req) {
        if (repository.existsById(req.setCode())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        MagicSet saved = repository.save(toEntity(new MagicSet(), req));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{code}")
    public ResponseEntity<MagicSet> update(@PathVariable("code") String code,
                                           @Valid @RequestBody SetRequest req) {
        if (!code.equals(req.setCode())) {
            return ResponseEntity.badRequest().build();
        }
        MagicSet existing = repository.findById(code).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.save(toEntity(existing, req)));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable("code") String code) {
        if (!repository.existsById(code)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(code);
        return ResponseEntity.noContent().build();
    }

    private static MagicSet toEntity(MagicSet target, SetRequest req) {
        target.setSetCode(req.setCode());
        target.setSetName(req.setName());
        target.setReleaseDate(req.releaseDate());
        target.setSetType(req.setType());
        target.setCardCount(req.cardCount());
        target.setPrintedSize(req.printedSize());
        target.setBlockCode(req.blockCode());
        target.setBlockName(req.blockName());
        return target;
    }

    /**
     * JSON body for create/update. Accepts snake_case keys to stay
     * consistent with the rest of the API contract.
     */
    public record SetRequest(
            @JsonProperty("set_code") @NotBlank String setCode,
            @JsonProperty("set_name") @NotBlank String setName,
            @JsonProperty("release_date") LocalDate releaseDate,
            @JsonProperty("set_type") String setType,
            @JsonProperty("card_count") Integer cardCount,
            @JsonProperty("printed_size") Integer printedSize,
            @JsonProperty("block_code") String blockCode,
            @JsonProperty("block_name") String blockName) {
    }
}
