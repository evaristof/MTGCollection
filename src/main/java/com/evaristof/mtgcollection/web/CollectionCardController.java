package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.service.CollectionCardService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/collection/cards")
public class CollectionCardController {

    private final CollectionCardService service;

    public CollectionCardController(CollectionCardService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CollectionCard> add(@Valid @RequestBody AddCardRequest req) {
        CollectionCard saved = service.addCardToCollection(
                req.cardName(), req.setCode(), req.foil(), req.language(), req.quantity());
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public List<CollectionCard> list(@RequestParam(value = "set", required = false) String set) {
        return set == null ? service.listAll() : service.listBySet(set);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CollectionCard> getOne(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(service.getById(id));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<CollectionCard> update(@PathVariable("id") Long id,
                                                 @Valid @RequestBody UpdateCardRequest req) {
        try {
            CollectionCard saved = service.update(
                    id, req.cardName(), req.setCode(), req.foil(), req.language(), req.quantity());
            return ResponseEntity.ok(saved);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        return service.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * JSON body for {@code POST /api/collection/cards}.
     *
     * <p>Accepts snake_case keys ({@code card_name}, {@code set_code}) to
     * match the rest of the API contract.</p>
     */
    public record AddCardRequest(
            @JsonProperty("card_name") @NotBlank String cardName,
            @JsonProperty("set_code") @NotBlank String setCode,
            boolean foil,
            @NotBlank String language,
            @Min(1) int quantity) {
    }

    /**
     * JSON body for {@code PUT /api/collection/cards/{id}}.
     *
     * <p>Allows tweaking the quantity/foil/language of an existing stack.
     * Name and set are optional; if supplied, they replace the stored
     * values.</p>
     */
    public record UpdateCardRequest(
            @JsonProperty("card_name") String cardName,
            @JsonProperty("set_code") String setCode,
            boolean foil,
            @NotBlank String language,
            @Min(1) int quantity) {
    }
}
