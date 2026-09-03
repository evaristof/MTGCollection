package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
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

    private final CardLookupService cardLookupService;
    private final CollectionCardRepository repository;

    public CollectionCardService(CardLookupService cardLookupService,
                                 CollectionCardRepository repository) {
        this.cardLookupService = cardLookupService;
        this.repository = repository;
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
                ? cardLookupService.getCardBySetAndNumber(setCode, collectorNumber.trim())
                : cardLookupService.getCardByNameAndSet(cardName, setCode);
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
                existing.setComentario(CardPriceResolver.applyEurFoilNote(
                        existing.getComentario(), resolvedPrice.isEurFoilFallback()));
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
            // Foil cujo preço veio em euro entra marcado, para o número não ser
            // lido como dólar (ver CardPriceResolver).
            entity.setComentario(CardPriceResolver.applyEurFoilNote(
                    entity.getComentario(), resolvedPrice.isEurFoilFallback()));
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
            card = cardLookupService.getCardBySetAndNumber(setCode, cardNumber);
        } else if (isNotBlank(setCode) && isNotBlank(cardName)) {
            card = cardLookupService.getCardByNameAndSet(cardName, setCode);
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
            // A marca de preço em euro é gerenciada aqui: entra quando o preço
            // veio de eur_foil e sai sozinha quando o dólar volta a existir.
            existing.setComentario(CardPriceResolver.applyEurFoilNote(
                    existing.getComentario(), resolvedPrice.isEurFoilFallback()));
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
