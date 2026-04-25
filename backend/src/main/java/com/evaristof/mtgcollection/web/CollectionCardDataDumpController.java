package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.CollectionCardDataDump;
import com.evaristof.mtgcollection.repository.CollectionCardDataDumpRepository;
import com.evaristof.mtgcollection.service.CollectionCardDataDumpService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Per-dump total value of the collection ({@code SUM(price * quantity)})
     * for every snapshot captured in {@code [from, to]}. Feeds the first
     * chart on the "Gráficos" page.
     *
     * <p>Both bounds are optional ISO-8601 local-datetimes (e.g.
     * {@code 2025-11-18T00:00}). If omitted, the range is unbounded on
     * that side. Malformed bounds return {@code 400}.</p>
     */
    @GetMapping("/stats/total-value")
    public ResponseEntity<?> totalValuePerDump(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        LocalDateTime parsedFrom = null;
        LocalDateTime parsedTo = null;
        if (from != null && !from.isBlank()) {
            parsedFrom = tryParse(from);
            if (parsedFrom == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "message", "from inválido: " + from));
            }
        }
        if (to != null && !to.isBlank()) {
            parsedTo = tryParse(to);
            if (parsedTo == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "message", "to inválido: " + to));
            }
        }
        List<CollectionCardDataDumpRepository.DumpTotal> rows =
                service.totalValuePerDump(parsedFrom, parsedTo);
        return ResponseEntity.ok(rows.stream().map(DumpTotalResponse::from).toList());
    }

    /**
     * Top-N cards that appreciated and depreciated the most between the two
     * most recent snapshots within {@code [from, to]}. Feeds the "movers"
     * tables on the Gráficos page.
     *
     * <p>Returns {@code 204 No Content} when fewer than two dumps exist in
     * the range (nothing to compare).</p>
     */
    @GetMapping("/stats/price-movers")
    public ResponseEntity<?> priceMovers(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        if (limit < 1) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "limit deve ser >= 1, recebido: " + limit));
        }
        LocalDateTime parsedFrom = null;
        LocalDateTime parsedTo = null;
        if (from != null && !from.isBlank()) {
            parsedFrom = tryParse(from);
            if (parsedFrom == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "message", "from inválido: " + from));
            }
        }
        if (to != null && !to.isBlank()) {
            parsedTo = tryParse(to);
            if (parsedTo == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "message", "to inválido: " + to));
            }
        }
        CollectionCardDataDumpService.PriceMoversResult result =
                service.priceMovers(parsedFrom, parsedTo, limit);
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new PriceMoversResponse(result));
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
     * Shape returned by {@code GET /stats/total-value}. One entry per
     * dump, already ordered chronologically, with the aggregated value
     * precomputed on the DB side.
     */
    public record DumpTotalResponse(
            @JsonProperty("data_dump_date_time") LocalDateTime dataDumpDateTime,
            @JsonProperty("total_value") BigDecimal totalValue) {

        static DumpTotalResponse from(CollectionCardDataDumpRepository.DumpTotal t) {
            BigDecimal value = t.totalValue() != null ? t.totalValue() : BigDecimal.ZERO;
            return new DumpTotalResponse(t.timestamp(), value);
        }
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

    /** Single card entry in the price-movers response. */
    public record CardMoverResponse(
            @JsonProperty("card_name") String cardName,
            @JsonProperty("set_code") String setCode,
            @JsonProperty("set_name_raw") String setNameRaw,
            boolean foil,
            String language,
            @JsonProperty("source_card_id") Long sourceCardId,
            @JsonProperty("price_old") BigDecimal priceOld,
            @JsonProperty("price_new") BigDecimal priceNew,
            @JsonProperty("price_diff") BigDecimal priceDiff) {

        static CardMoverResponse from(CollectionCardDataDumpService.CardMover m) {
            return new CardMoverResponse(
                    m.cardName(), m.setCode(), m.setNameRaw(), m.foil(),
                    m.language(), m.sourceCardId(),
                    m.priceOld(), m.priceNew(), m.priceDiff());
        }
    }

    /** Wraps both mover lists together with the compared snapshot timestamps. */
    public record PriceMoversResponse(
            @JsonProperty("old_timestamp") LocalDateTime oldTimestamp,
            @JsonProperty("new_timestamp") LocalDateTime newTimestamp,
            @JsonProperty("top_gainers") java.util.List<CardMoverResponse> topGainers,
            @JsonProperty("top_losers") java.util.List<CardMoverResponse> topLosers) {

        PriceMoversResponse(CollectionCardDataDumpService.PriceMoversResult r) {
            this(r.oldTimestamp(), r.newTimestamp(),
                    r.gainers().stream().map(CardMoverResponse::from).toList(),
                    r.losers().stream().map(CardMoverResponse::from).toList());
        }
    }
}
