package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
