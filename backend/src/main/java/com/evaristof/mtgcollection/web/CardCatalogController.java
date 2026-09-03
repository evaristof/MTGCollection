package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CardCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only helpers for the "Cadastro Cartas" fast-entry screen, backed by
 * {@link CardCatalogService} (the {@code CARD_IMAGE_HASH} reference
 * catalog).
 */
@RestController
@RequestMapping("/api/card-catalog")
public class CardCatalogController {

    private final CardCatalogService service;

    public CardCatalogController(CardCatalogService service) {
        this.service = service;
    }

    @GetMapping("/names")
    public List<String> names() {
        return service.allCardNames();
    }

    @GetMapping("/sets")
    public List<CardCatalogService.SetOption> sets(@RequestParam("name") String name) {
        return service.setsForCardName(name);
    }

    /** Resolves the card name printed at (set, number) — fills the name field from a typed number. */
    @GetMapping("/lookup-number")
    public ResponseEntity<Map<String, String>> lookupNumber(@RequestParam("set") String set,
                                                             @RequestParam("number") String number) {
        return service.lookupCardName(set, number)
                .map(name -> ResponseEntity.ok(Map.of("card_name", name)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Resolves a collector number for (set, name) — used for the image preview when no number was typed. */
    @GetMapping("/resolve-number")
    public ResponseEntity<Map<String, String>> resolveNumber(@RequestParam("set") String set,
                                                              @RequestParam("name") String name) {
        return service.resolveCollectorNumber(set, name)
                .map(number -> ResponseEntity.ok(Map.of("collector_number", number)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
