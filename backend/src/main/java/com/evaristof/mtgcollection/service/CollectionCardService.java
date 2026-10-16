package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Persists cards in the user's MTG collection.
 *
 * <p>Uses {@link CardLookupService} to fetch the card's collector number and
 * type line from Scryfall. The caller supplies the language and quantity
 * (plus name/set/foil) because those are user-chosen attributes of a specific
 * physical copy, not something we can derive from the Scryfall payload.</p>
 */
@Service
public class CollectionCardService {

    private static final Logger log = LoggerFactory.getLogger(CollectionCardService.class);

    private final CardLookupService cardLookupService;
    private final CollectionCardRepository repository;
    private final CardBatchLookupService cardBatchLookupService;
    private final long syncThrottleMs;

    public CollectionCardService(CardLookupService cardLookupService,
                                 CollectionCardRepository repository,
                                 CardBatchLookupService cardBatchLookupService,
                                 @Value("${mtg.collection-sync.throttle-ms:100}") long syncThrottleMs) {
        this.cardLookupService = cardLookupService;
        this.repository = repository;
        this.cardBatchLookupService = cardBatchLookupService;
        this.syncThrottleMs = Math.max(0L, syncThrottleMs);
    }

    @Transactional
    public CollectionCard addCardToCollection(String cardName, String setCode, boolean foil,
                                              String language, int quantity) {
        return addCardToCollection(cardName, setCode, foil, language, quantity, null, null);
    }

    /**
     * Adds a card to the collection. When {@code collectorNumber} is supplied,
     * the card is resolved by ({@code setCode}, {@code collectorNumber}) — more
     * precise, and what the scanner provides; otherwise by ({@code cardName},
     * {@code setCode}). Collector number, type line and price all come from that
     * same Scryfall lookup.
     *
     * <p>Identical copies stack instead of duplicating: when the collection
     * already has a row with the same set, collector number (or name, for rows
     * stored without a number), foil, language and location, that row's
     * {@code QUANTITY} grows by {@code quantity} and no new row is created.</p>
     */
    @Transactional
    public CollectionCard addCardToCollection(String cardName,
                                              String setCode,
                                              boolean foil,
                                              String language,
                                              int quantity,
                                              Location location,
                                              String collectorNumber) {
        boolean byNumber = collectorNumber != null && !collectorNumber.isBlank();
        if (setCode == null || setCode.isBlank()) {
            throw new IllegalArgumentException("setCode must not be blank");
        }
        if (!byNumber && (cardName == null || cardName.isBlank())) {
            throw new IllegalArgumentException("cardName must not be blank when collectorNumber is absent");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }

        ScryfallCard card = byNumber
                ? cardLookupService.getCardBySetAndNumber(setCode, collectorNumber.trim(), foil)
                : cardLookupService.getCardByNameAndSet(cardName, setCode, foil);
        if (card == null) {
            throw new IllegalStateException("Scryfall returned no card for "
                    + (byNumber ? "set=" + setCode + " number=" + collectorNumber
                                : "name=" + cardName + " set=" + setCode));
        }

        String resolvedSet = card.getSet() != null ? card.getSet() : setCode;
        String resolvedNumber = card.getCollectorNumber() != null ? card.getCollectorNumber()
                : (byNumber ? collectorNumber.trim() : null);
        String resolvedName = card.getName() != null ? card.getName() : cardName;
        CardPriceResolver.Resolved resolvedPrice = CardPriceResolver.resolve(card, foil);
        BigDecimal price = resolvedPrice.price();

        CollectionCard existing = findStack(resolvedSet, resolvedNumber, resolvedName, foil, language, location);
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + quantity);
            if (price != null) {
                existing.setPrice(price);
                existing.setComentario(CardPriceResolver.applyPriceNote(
                        existing.getComentario(), resolvedPrice));
            }
            return repository.save(existing);
        }

        CollectionCard entity = new CollectionCard();
        entity.setCardNumber(resolvedNumber);
        entity.setCardName(resolvedName);
        entity.setSetCode(resolvedSet);
        entity.setFoil(foil);
        entity.setCardType(card.getTypeLine());
        entity.setLanguage(language);
        entity.setQuantity(quantity);
        entity.setPrice(price);
        if (price != null) {
            // Foil cotada em etched ou em euro entra marcada, para o número não
            // ser lido como um usd_foil qualquer (ver CardPriceResolver).
            entity.setComentario(CardPriceResolver.applyPriceNote(
                    entity.getComentario(), resolvedPrice));
        }
        entity.setLocation(location);
        return repository.save(entity);
    }

    /**
     * The stack this add should merge into, or {@code null} when the collection
     * doesn't have this card under that exact identity yet.
     *
     * <p>Candidates are fetched keyed by (set, collector number, foil) — or by
     * (set, name, foil) for cards stored without a collector number, e.g. from
     * a spreadsheet import. Language and location are matched here rather than
     * in the query: language case-insensitively (so "EN" stacks with "en") and
     * location by FK id.</p>
     */
    private CollectionCard findStack(String setCode,
                                     String cardNumber,
                                     String cardName,
                                     boolean foil,
                                     String language,
                                     Location location) {
        List<CollectionCard> candidates;
        if (cardNumber != null && !cardNumber.isBlank()) {
            candidates = repository.findAllBySetCodeAndCardNumberAndFoil(setCode, cardNumber, foil);
        } else if (cardName != null && !cardName.isBlank()) {
            candidates = repository.findAllBySetCodeAndCardNameIgnoreCaseAndFoil(setCode, cardName, foil);
        } else {
            return null;
        }
        Long locationId = location != null ? location.getId() : null;
        return candidates.stream()
                .filter(c -> sameLanguage(language, c.getLanguage()))
                .filter(c -> Objects.equals(locationId, c.getLocationId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean sameLanguage(String a, String b) {
        return Objects.equals(normalizeLanguage(a), normalizeLanguage(b));
    }

    private static String normalizeLanguage(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public List<CollectionCard> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<CollectionCard> listBySet(String setCode) {
        return repository.findBySetCode(setCode);
    }

    @Transactional(readOnly = true)
    public CollectionCard getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "CollectionCard not found: id=" + id));
    }

    /**
     * Updates mutable attributes of an existing collection entry.
     *
     * <p>Semantics per argument:
     * <ul>
     *   <li>{@code cardName} / {@code setCode}: {@code null} or blank →
     *       do not change; otherwise replace.</li>
     *   <li>{@code foil} / {@code language} / {@code quantity}: always
     *       replaced (required fields on the request).</li>
     *   <li>{@code cardType} / {@code comentario}: {@code null} → do not
     *       change; empty string → clear (persist {@code null}); otherwise
     *       replace (trimmed).</li>
     *   <li>{@code location}: only touched when {@code changeLocation} is
     *       {@code true} — then {@code null} clears the FK and any other value
     *       replaces it. Callers resolve names to catalog rows beforehand
     *       (see {@link LocationService#resolve}).</li>
     *   <li>{@code price}: {@code null} → do not change; otherwise
     *       replace (callers that wish to clear the price must fetch the
     *       row, null it out, then persist explicitly — we do not overload
     *       a sentinel here because {@link java.math.BigDecimal} already
     *       distinguishes zero from "unset").</li>
     * </ul>
     */
    @Transactional
    public CollectionCard update(Long id,
                                 String cardName,
                                 String setCode,
                                 boolean foil,
                                 String language,
                                 int quantity,
                                 String cardType,
                                 BigDecimal price,
                                 String comentario,
                                 Location location,
                                 boolean changeLocation) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        CollectionCard existing = getById(id);
        if (cardName != null && !cardName.isBlank()) {
            existing.setCardName(cardName);
        }
        if (setCode != null && !setCode.isBlank()) {
            existing.setSetCode(setCode);
        }
        existing.setFoil(foil);
        existing.setLanguage(language);
        existing.setQuantity(quantity);
        if (cardType != null) {
            String trimmed = cardType.trim();
            existing.setCardType(trimmed.isEmpty() ? null : trimmed);
        }
        if (price != null) {
            existing.setPrice(price);
        }
        if (comentario != null) {
            String trimmed = comentario.trim();
            existing.setComentario(trimmed.isEmpty() ? null : trimmed);
        }
        if (changeLocation) {
            existing.setLocation(location);
        }
        return repository.save(existing);
    }

    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    /**
     * Refreshes many rows at once via Scryfall's batch endpoint
     * ({@code POST /cards/collection}, up to 75 identifiers per request) —
     * the same technique {@link CollectionImportService} uses for
     * spreadsheet imports, applied here to the "Sincronizar selecionados"
     * button. Where {@link #syncCard(Long)} issues one HTTP round trip per
     * row, this issues one or two round trips total, no matter how many
     * rows are selected.
     *
     * <p>Mirrors {@code syncCard}'s row semantics exactly: same lookup
     * precedence (set+number, else set+name), same price resolution via
     * {@link CardPriceResolver}, and the same starred-foil-print retry that
     * {@link CardLookupService#getCardBySetAndNumber(String, String, boolean)}
     * does for old core sets — just batched. A second {@code /cards/collection}
     * call is issued only for the foil rows whose plain print came back
     * without a foil price.</p>
     *
     * <p>{@code job} is updated as rows are processed so a caller running
     * this on a background thread (see {@code DataJobService}) can report
     * progress; this method itself runs synchronously to completion.</p>
     */
    @Transactional
    public void syncCardsBatch(DataJob job, List<Long> ids) {
        List<CollectionCard> rows = repository.findAllById(ids);
        job.setTotal(rows.size());

        List<ScryfallCardIdentifier> identifiers = new ArrayList<>(rows.size());
        for (CollectionCard c : rows) {
            ScryfallCardIdentifier identifier = identifierFor(c);
            if (identifier != null) {
                identifiers.add(identifier);
            }
        }

        Map<String, ScryfallCard> index;
        try {
            List<ScryfallCardIdentifier> notFound = new ArrayList<>();
            List<ScryfallCard> matched = cardBatchLookupService.getCardsBatch(identifiers, syncThrottleMs, notFound);
            index = indexCards(matched);
            if (!notFound.isEmpty()) {
                log.info("Scryfall /cards/collection reportou {} identificador(es) não encontrado(s) na sincronização em lote",
                        notFound.size());
            }
        } catch (ScryfallLookupException e) {
            for (CollectionCard c : rows) {
                job.addError(rowLabel(c) + ": " + e.getMessage());
                job.incrementProcessed();
            }
            job.setMessage("Falha na consulta em lote ao Scryfall: " + e.getMessage());
            return;
        }

        retryStarredFoilPrints(rows, index);

        List<CollectionCard> toSave = new ArrayList<>(rows.size());
        for (CollectionCard c : rows) {
            ScryfallCard card = index.get(matchKeyFor(c));
            if (card == null) {
                job.addError(rowLabel(c) + ": Scryfall não retornou a carta");
                job.incrementProcessed();
                continue;
            }
            if (card.getTypeLine() != null) {
                c.setCardType(card.getTypeLine());
            }
            CardPriceResolver.Resolved resolvedPrice = CardPriceResolver.resolve(card, c.isFoil());
            if (resolvedPrice.price() != null) {
                c.setPrice(resolvedPrice.price());
                c.setComentario(CardPriceResolver.applyPriceNote(c.getComentario(), resolvedPrice));
                job.incrementSucceeded();
            } else {
                job.addError(rowLabel(c) + ": sem preço disponível na Scryfall");
            }
            if (isBlank(c.getCardNumber()) && card.getCollectorNumber() != null) {
                c.setCardNumber(card.getCollectorNumber());
            }
            toSave.add(c);
            job.incrementProcessed();
        }
        repository.saveAll(toSave);
        job.setMessage(job.getSucceeded() + " de " + job.getTotal() + " carta(s) sincronizada(s)"
                + (job.getErrors().isEmpty() ? "." : ", " + job.getErrors().size() + " erro(s)."));
    }

    /**
     * Second batch round for rows that want a foil price but whose plain
     * print has none — the old-core-set "parallel foil print" case (see
     * {@link CardPriceResolver#FOIL_STAR_SUFFIX}). Updates {@code index} in
     * place so the main loop in {@link #syncCardsBatch} picks up the
     * starred print under the row's original (plain) match key.
     */
    private void retryStarredFoilPrints(List<CollectionCard> rows, Map<String, ScryfallCard> index) {
        List<ScryfallCardIdentifier> starIdentifiers = new ArrayList<>();
        for (CollectionCard c : rows) {
            if (!c.isFoil()) continue;
            ScryfallCard card = index.get(matchKeyFor(c));
            if (card == null || CardPriceResolver.resolve(card, true).hasPrice()) continue;
            String number = card.getCollectorNumber();
            String set = card.getSet();
            if (number == null || number.isBlank() || set == null || set.isBlank()
                    || number.endsWith(CardPriceResolver.FOIL_STAR_SUFFIX)) {
                continue;
            }
            starIdentifiers.add(ScryfallCardIdentifier.bySetAndNumber(set, number + CardPriceResolver.FOIL_STAR_SUFFIX));
        }
        if (starIdentifiers.isEmpty()) {
            return;
        }
        try {
            List<ScryfallCard> starred = cardBatchLookupService.getCardsBatch(starIdentifiers, syncThrottleMs, null);
            for (ScryfallCard card : starred) {
                if (card == null || card.getSet() == null || card.getCollectorNumber() == null) continue;
                // Index the starred (priced) print under the PLAIN number's key
                // — rows keep their plain card_number, so that's the key the
                // main loop will look it up by.
                String plainNumber = stripStarSuffix(card.getCollectorNumber());
                index.put(keyBySetAndNumber(card.getSet(), plainNumber), card);
                if (card.getName() != null) {
                    index.put(keyByNameAndSet(card.getSet(), card.getName()), card);
                }
            }
        } catch (ScryfallLookupException e) {
            // Most cards simply have no parallel-foil print — not fatal, the
            // plain print already indexed stays in use for those rows.
            log.debug("Starred-foil batch retry failed: {}", e.getMessage());
        }
    }

    private static String stripStarSuffix(String collectorNumber) {
        return collectorNumber.endsWith(CardPriceResolver.FOIL_STAR_SUFFIX)
                ? collectorNumber.substring(0, collectorNumber.length() - CardPriceResolver.FOIL_STAR_SUFFIX.length())
                : collectorNumber;
    }

    private static String rowLabel(CollectionCard c) {
        return "#" + c.getId() + " (" + (c.getCardName() != null ? c.getCardName() : "?") + ")";
    }

    /** Same lookup precedence as {@link #syncCard(Long)}: set+number, else set+name. */
    private static ScryfallCardIdentifier identifierFor(CollectionCard c) {
        String setCode = c.getSetCode();
        if (isBlank(setCode)) return null;
        if (isNotBlank(c.getCardNumber())) {
            return ScryfallCardIdentifier.bySetAndNumber(setCode, c.getCardNumber());
        }
        if (isNotBlank(c.getCardName())) {
            return ScryfallCardIdentifier.byNameAndSet(c.getCardName(), setCode);
        }
        return null;
    }

    /** The key a row's own (set, number)/(set, name) resolves to in the index below. */
    private static String matchKeyFor(CollectionCard c) {
        String setCode = c.getSetCode();
        if (isBlank(setCode)) return null;
        if (isNotBlank(c.getCardNumber())) {
            return keyBySetAndNumber(setCode, c.getCardNumber());
        }
        if (isNotBlank(c.getCardName())) {
            return keyByNameAndSet(setCode, c.getCardName());
        }
        return null;
    }

    private static Map<String, ScryfallCard> indexCards(List<ScryfallCard> cards) {
        Map<String, ScryfallCard> index = new HashMap<>(cards.size() * 2);
        for (ScryfallCard card : cards) {
            if (card == null || card.getSet() == null) continue;
            if (card.getCollectorNumber() != null) {
                index.putIfAbsent(keyBySetAndNumber(card.getSet(), card.getCollectorNumber()), card);
            }
            if (card.getName() != null) {
                index.putIfAbsent(keyByNameAndSet(card.getSet(), card.getName()), card);
            }
        }
        return index;
    }

    private static String keyBySetAndNumber(String setCode, String collectorNumber) {
        return "n|" + normalize(setCode) + "|" + normalize(collectorNumber);
    }

    private static String keyByNameAndSet(String setCode, String cardName) {
        return "c|" + normalize(setCode) + "|" + normalize(cardName);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Refreshes the Scryfall-derived fields ({@code cardType} and {@code price})
     * of a single row by querying Scryfall and persisting the result.
     *
     * <p>Lookup precedence mirrors the import flow: if {@code setCode} and
     * {@code cardNumber} are both filled, we use
     * {@code GET /cards/{set}/{number}}; otherwise we fall back to
     * {@code GET /cards/named?exact=<name>&set=<code>}. The resolved
     * {@code collector_number} is also written back when it was empty.</p>
     *
     * @throws java.util.NoSuchElementException if no row with that id exists
     * @throws IllegalStateException if the row lacks enough data to look up
     *         (no name+set and no set+number), or Scryfall returns no card
     * @throws ScryfallLookupException on network/API errors
     */
    @Transactional
    public CollectionCard syncCard(Long id) {
        CollectionCard existing = getById(id);

        String setCode = existing.getSetCode();
        String cardNumber = existing.getCardNumber();
        String cardName = existing.getCardName();

        ScryfallCard card;
        if (isNotBlank(setCode) && isNotBlank(cardNumber)) {
            card = cardLookupService.getCardBySetAndNumber(setCode, cardNumber, existing.isFoil());
        } else if (isNotBlank(setCode) && isNotBlank(cardName)) {
            card = cardLookupService.getCardByNameAndSet(cardName, setCode, existing.isFoil());
        } else {
            throw new IllegalStateException(
                    "Linha id=" + id + " sem dados suficientes para sincronizar "
                            + "(precisa de set + número, ou set + nome).");
        }

        if (card == null) {
            throw new IllegalStateException(
                    "Scryfall não retornou carta para id=" + id
                            + " (name=" + cardName + ", set=" + setCode
                            + ", number=" + cardNumber + ")");
        }

        if (card.getTypeLine() != null) {
            existing.setCardType(card.getTypeLine());
        }
        CardPriceResolver.Resolved resolvedPrice = CardPriceResolver.resolve(card, existing.isFoil());
        if (resolvedPrice.price() != null) {
            existing.setPrice(resolvedPrice.price());
            // As marcas de origem do preço são gerenciadas aqui: entram, trocam
            // entre si e somem sozinhas conforme a fonte muda entre uma
            // sincronização e outra.
            existing.setComentario(CardPriceResolver.applyPriceNote(
                    existing.getComentario(), resolvedPrice));
        }
        if (isBlank(existing.getCardNumber()) && card.getCollectorNumber() != null) {
            existing.setCardNumber(card.getCollectorNumber());
        }

        return repository.save(existing);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

}
