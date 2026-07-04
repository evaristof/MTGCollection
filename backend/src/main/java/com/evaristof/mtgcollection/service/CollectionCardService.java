package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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
     */
    public CollectionCard addCardToCollection(String cardName,
                                              String setCode,
                                              boolean foil,
                                              String language,
                                              int quantity,
                                              String localizacao,
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
        String normLoc = normalizeLoc(localizacao);
        BigDecimal price = priceFrom(card, foil);

        // Merge into an existing identical stack (same set + number + foil +
        // language + location) instead of creating a duplicate row.
        if (resolvedNumber != null) {
            CollectionCard existing = repository
                    .findAllBySetCodeAndCardNumberAndFoilAndLanguage(resolvedSet, resolvedNumber, foil, language)
                    .stream()
                    .filter(c -> java.util.Objects.equals(normLoc, normalizeLoc(c.getLocalizacao())))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.setQuantity(existing.getQuantity() + quantity);
                if (price != null) {
                    existing.setPrice(price);
                }
                return repository.save(existing);
            }
        }

        CollectionCard entity = new CollectionCard();
        entity.setCardNumber(resolvedNumber);
        entity.setCardName(card.getName() != null ? card.getName() : cardName);
        entity.setSetCode(resolvedSet);
        entity.setFoil(foil);
        entity.setCardType(card.getTypeLine());
        entity.setLanguage(language);
        entity.setQuantity(quantity);
        entity.setPrice(price);
        entity.setLocalizacao(normLoc);
        return repository.save(entity);
    }

    private static String normalizeLoc(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
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
     *   <li>{@code cardType} / {@code comentario} / {@code localizacao}:
     *       {@code null} → do not change; empty string → clear
     *       (persist {@code null}); otherwise replace (trimmed).</li>
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
                                 String localizacao) {
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
        if (localizacao != null) {
            String trimmed = localizacao.trim();
            existing.setLocalizacao(trimmed.isEmpty() ? null : trimmed);
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
        BigDecimal price = priceFrom(card, existing.isFoil());
        if (price != null) {
            existing.setPrice(price);
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

    private static BigDecimal priceFrom(ScryfallCard card, boolean foil) {
        if (card == null || card.getPrices() == null) return null;
        ScryfallPrices prices = card.getPrices();
        String raw = foil ? prices.getUsdFoil() : prices.getUsd();
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
