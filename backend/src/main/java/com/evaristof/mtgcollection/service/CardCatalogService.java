package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Backs the "Cadastro Cartas" registration screen's fast-entry helpers: card
 * name autocomplete, the sets a given card was printed in, and looking a
 * card up by (set, collector number) or vice-versa.
 *
 * <p>Everything here is sourced from the {@code CARD_IMAGE_HASH} reference
 * catalog (populated by the scanner's Scryfall image sync — see
 * {@link CardImageMatchService}), never from a live Scryfall call, so it
 * stays fast even when registering hundreds of cards in a row.</p>
 */
@Service
public class CardCatalogService {

    private final CardImageHashRepository cardImageHashRepository;
    private final MagicSetRepository magicSetRepository;

    public CardCatalogService(CardImageHashRepository cardImageHashRepository,
                              MagicSetRepository magicSetRepository) {
        this.cardImageHashRepository = cardImageHashRepository;
        this.magicSetRepository = magicSetRepository;
    }

    @Transactional(readOnly = true)
    public List<String> allCardNames() {
        return cardImageHashRepository.findDistinctCardNames();
    }

    /** One entry in the sets-for-a-card dropdown. */
    public record SetOption(String setCode, String setName) {
    }

    /**
     * Sets that contain a printing of {@code cardName}, ordered by set name.
     * Falls back to the set code as the label when the set isn't (yet)
     * present in the persisted {@code MAGIC_SET} table.
     */
    @Transactional(readOnly = true)
    public List<SetOption> setsForCardName(String cardName) {
        if (cardName == null || cardName.isBlank()) {
            return List.of();
        }
        List<String> codes = cardImageHashRepository.findDistinctSetCodesByCardNameIgnoreCase(cardName);
        return codes.stream()
                .map(code -> {
                    MagicSet set = magicSetRepository.findById(code).orElse(null);
                    return new SetOption(code, set != null ? set.getSetName() : code);
                })
                .sorted(Comparator.comparing(SetOption::setName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Card name printed at (set, collectorNumber), if known. */
    @Transactional(readOnly = true)
    public Optional<String> lookupCardName(String setCode, String collectorNumber) {
        if (setCode == null || setCode.isBlank() || collectorNumber == null || collectorNumber.isBlank()) {
            return Optional.empty();
        }
        return cardImageHashRepository.findBySetCodeAndCollectorNumber(setCode.trim(), collectorNumber.trim())
                .map(CardImageHash::getCardName);
    }

    /**
     * A collector number for (set, cardName) — used to build the image
     * preview URL ({@code /api/scanner/image/{set}/{number}}) when the user
     * hasn't typed a collector number yet.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveCollectorNumber(String setCode, String cardName) {
        if (setCode == null || setCode.isBlank() || cardName == null || cardName.isBlank()) {
            return Optional.empty();
        }
        return cardImageHashRepository
                .findFirstBySetCodeAndCardNameIgnoreCaseOrderByCollectorNumberAsc(setCode.trim(), cardName.trim())
                .map(CardImageHash::getCollectorNumber);
    }
}
