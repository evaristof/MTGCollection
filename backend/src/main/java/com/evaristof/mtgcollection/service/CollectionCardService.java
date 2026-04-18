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
    public CollectionCard addCardToCollection(String cardName,
                                              String setCode,
                                              boolean foil,
                                              String language,
                                              int quantity) {
        if (cardName == null || cardName.isBlank()) {
            throw new IllegalArgumentException("cardName must not be blank");
        }
        if (setCode == null || setCode.isBlank()) {
            throw new IllegalArgumentException("setCode must not be blank");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }

        ScryfallCard card = cardLookupService.getCardByNameAndSet(cardName, setCode);
        if (card == null) {
            throw new IllegalStateException(
                    "Scryfall returned no card for name=" + cardName + " set=" + setCode);
        }

        CollectionCard entity = new CollectionCard();
        entity.setCardNumber(card.getCollectorNumber());
        entity.setCardName(card.getName() != null ? card.getName() : cardName);
        entity.setSetCode(card.getSet() != null ? card.getSet() : setCode);
        entity.setFoil(foil);
        entity.setCardType(card.getTypeLine());
        entity.setLanguage(language);
        entity.setQuantity(quantity);
        return repository.save(entity);
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
     * Updates mutable attributes of an existing collection entry. Does not
     * touch scryfall-derived fields ({@code cardNumber}, {@code cardType})
     * because those are stable for a given (set, number); use
     * {@link #addCardToCollection} for a fresh lookup.
     */
    @Transactional
    public CollectionCard update(Long id,
                                 String cardName,
                                 String setCode,
                                 boolean foil,
                                 String language,
                                 int quantity) {
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
