package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CollectionCardService#syncCard(Long)} — the per-row Scryfall
 * refresh wired up to the "Sincronizar" button on the Cards page.
 */
class CollectionCardServiceSyncTest {

    private CardLookupService cardLookupService;
    private CollectionCardRepository repository;
    private CollectionCardService service;

    @BeforeEach
    void setUp() {
        cardLookupService = mock(CardLookupService.class);
        repository = mock(CollectionCardRepository.class);
        service = new CollectionCardService(cardLookupService, repository);
        when(repository.save(any(CollectionCard.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CollectionCard row(String name, String set, String number, boolean foil) {
        CollectionCard c = new CollectionCard();
        c.setId(10L);
        c.setCardName(name);
        c.setSetCode(set);
        c.setCardNumber(number);
        c.setFoil(foil);
        c.setLanguage("en");
        c.setQuantity(1);
        return c;
    }

    private static ScryfallCard scryfall(String name, String set, String number, String typeLine,
                                         String usd, String usdFoil) {
        ScryfallCard c = new ScryfallCard();
        c.setName(name);
        c.setSet(set);
        c.setCollectorNumber(number);
        c.setTypeLine(typeLine);
        ScryfallPrices p = new ScryfallPrices();
        p.setUsd(usd);
        p.setUsdFoil(usdFoil);
        c.setPrices(p);
        return c;
    }

    @Test
    void syncCard_prefersSetAndNumberWhenBothPresent() {
        CollectionCard existing = row("Treachery", "ulg", "23", true);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(cardLookupService.getCardBySetAndNumber("ulg", "23"))
                .thenReturn(scryfall("Treachery", "ulg", "23", "Enchantment — Aura", "250.00", "400.00"));

        CollectionCard updated = service.syncCard(10L);

        assertThat(updated.getCardType()).isEqualTo("Enchantment — Aura");
        assertThat(updated.getPrice()).isEqualByComparingTo(new BigDecimal("400.00"));
        verify(cardLookupService).getCardBySetAndNumber("ulg", "23");
        verify(repository).save(existing);
    }

    @Test
    void syncCard_fallsBackToNameAndSetWhenNumberMissing() {
        CollectionCard existing = row("Treachery", "ulg", null, false);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(cardLookupService.getCardByNameAndSet("Treachery", "ulg"))
                .thenReturn(scryfall("Treachery", "ulg", "23", "Enchantment — Aura", "250.00", "400.00"));

        CollectionCard updated = service.syncCard(10L);

        assertThat(updated.getCardType()).isEqualTo("Enchantment — Aura");
        assertThat(updated.getPrice()).isEqualByComparingTo(new BigDecimal("250.00"));
        // non-foil → usd, not usd_foil
        assertThat(updated.getCardNumber()).isEqualTo("23");
    }

    @Test
    void syncCard_picksFoilPriceWhenRowIsFoil() {
        CollectionCard existing = row("Treachery", "ulg", "23", true);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(cardLookupService.getCardBySetAndNumber("ulg", "23"))
                .thenReturn(scryfall("Treachery", "ulg", "23", "Enchantment — Aura", "250.00", "400.00"));

        CollectionCard updated = service.syncCard(10L);

        assertThat(updated.getPrice()).isEqualByComparingTo(new BigDecimal("400.00"));
    }

    @Test
    void syncCard_keepsExistingTypeWhenScryfallHasNoTypeLine() {
        CollectionCard existing = row("X", "set", "1", false);
        existing.setCardType("Original Type");
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        ScryfallCard card = scryfall("X", "set", "1", null, "1.00", null);
        when(cardLookupService.getCardBySetAndNumber("set", "1")).thenReturn(card);

        CollectionCard updated = service.syncCard(10L);

        assertThat(updated.getCardType()).isEqualTo("Original Type");
        assertThat(updated.getPrice()).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void syncCard_keepsExistingPriceWhenScryfallHasNoPrice() {
        CollectionCard existing = row("X", "set", "1", false);
        existing.setPrice(new BigDecimal("9.99"));
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        ScryfallCard card = scryfall("X", "set", "1", "Instant", null, null);
        when(cardLookupService.getCardBySetAndNumber("set", "1")).thenReturn(card);

        CollectionCard updated = service.syncCard(10L);

        assertThat(updated.getPrice()).isEqualByComparingTo(new BigDecimal("9.99"));
        assertThat(updated.getCardType()).isEqualTo("Instant");
    }

    @Test
    void syncCard_throwsWhenRowNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.syncCard(99L))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(cardLookupService);
    }

    @Test
    void syncCard_throwsWhenRowHasNoSet() {
        CollectionCard existing = row("Treachery", null, null, false);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.syncCard(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sem dados suficientes");
        verifyNoInteractions(cardLookupService);
    }

    @Test
    void syncCard_throwsWhenScryfallReturnsNull() {
        CollectionCard existing = row("Ghost", "neo", "1", false);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(cardLookupService.getCardBySetAndNumber("neo", "1")).thenReturn(null);

        assertThatThrownBy(() -> service.syncCard(10L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void syncCard_propagatesScryfallLookupException() {
        CollectionCard existing = row("X", "neo", "1", false);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(cardLookupService.getCardBySetAndNumber("neo", "1"))
                .thenThrow(new ScryfallLookupException(
                        "https://api.scryfall.com/cards/neo/1", "HTTP 404", null));

        assertThatThrownBy(() -> service.syncCard(10L))
                .isInstanceOf(ScryfallLookupException.class);
    }
}
