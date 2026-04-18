package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.CollectionCardDataDump;
import com.evaristof.mtgcollection.service.CollectionCardDataDumpService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * REST endpoints for point-in-time snapshots of the user's collection.
 *
 * <p>The Cards page uses these to (1) capture a new dump, (2) list every
 * dump already captured, (3) load the grid from a specific dump, and
 * (4) delete a dump.</p>
 */
@RestController
@RequestMapping("/api/collection/datadumps")
public class CollectionCardDataDumpController {

    private final CollectionCardDataDumpService service;

    public CollectionCardDataDumpController(CollectionCardDataDumpService service) {
        this.service = service;
    }

    /**
     * Captures a snapshot of the current collection. Returns the timestamp
     * assigned to the new dump so the frontend can select it immediately.
     */
    @PostMapping
    public ResponseEntity<DumpCreatedResponse> create() {
        LocalDateTime timestamp = service.createDump();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DumpCreatedResponse(timestamp));
    }

    /** Distinct dump timestamps, most-recent first. */
    @GetMapping
    public List<LocalDateTime> list() {
        return service.listDumpTimestamps();
    }

    /**
     * Cards captured at {@code timestamp} (ISO-8601, e.g.
     * {@code 2025-11-18T10:22:31}). Returns {@code 404} when no rows exist
     * for the timestamp, {@code 400} when the path variable is malformed.
     */
    @GetMapping("/{timestamp}/cards")
    public ResponseEntity<?> cardsAt(@PathVariable("timestamp") String timestamp) {
        LocalDateTime parsed = tryParse(timestamp);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "timestamp inválido: " + timestamp
                            + " (use ISO-8601, ex.: 2025-11-18T10:22:31)"));
        }
        List<CollectionCardDataDump> rows = service.listCardsAt(parsed);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("message", "Nenhum dump encontrado para " + timestamp));
        }
        return ResponseEntity.ok(rows.stream().map(DumpCardResponse::from).toList());
    }

    @DeleteMapping("/{timestamp}")
    public ResponseEntity<?> delete(@PathVariable("timestamp") String timestamp) {
        LocalDateTime parsed = tryParse(timestamp);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "timestamp inválido: " + timestamp));
        }
        return service.deleteDump(parsed)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(java.util.Map.of("message", "Nenhum dump encontrado para " + timestamp));
    }

    private static LocalDateTime tryParse(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Response body for {@code POST /api/collection/datadumps}. */
    public record DumpCreatedResponse(
            @JsonProperty("data_dump_date_time") LocalDateTime dataDumpDateTime) {
    }

    /**
     * Shape returned by {@code GET /api/collection/datadumps/{ts}/cards}.
     *
     * <p>Mirrors {@link com.evaristof.mtgcollection.domain.CollectionCard}
     * as the frontend expects, so the existing grid rendering can consume
     * it unchanged. {@code id} is the dump row's own id (not the source
     * card id) so per-row UI keys remain unique even when the underlying
     * collection card is deleted.</p>
     */
    public record DumpCardResponse(
            long id,
            @JsonProperty("source_card_id") Long sourceCardId,
            @JsonProperty("data_dump_date_time") LocalDateTime dataDumpDateTime,
            @JsonProperty("card_number") String cardNumber,
            @JsonProperty("card_name") String cardName,
            @JsonProperty("set_code") String setCode,
            boolean foil,
            @JsonProperty("card_type") String cardType,
            String language,
            int quantity,
            BigDecimal price,
            String comentario,
            String localizacao) {

        static DumpCardResponse from(CollectionCardDataDump d) {
            return new DumpCardResponse(
                    d.getId(),
                    d.getSourceCardId(),
                    d.getDataDumpDateTime(),
                    d.getCardNumber(),
                    d.getCardName(),
                    d.getSetCode(),
                    d.isFoil(),
                    d.getCardType(),
                    d.getLanguage(),
                    d.getQuantity(),
                    d.getPrice(),
                    d.getComentario(),
                    d.getLocalizacao());
        }
    }
}
