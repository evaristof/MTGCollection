package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
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
import static org.mockito.Mockito.when;

class CollectionCardServiceUpdateDeleteTest {

    private CollectionCardRepository repository;
    private CollectionCardService service;

    @BeforeEach
    void setUp() {
        repository = mock(CollectionCardRepository.class);
        service = new CollectionCardService(mock(CardLookupService.class), repository);
    }

    private static CollectionCard existing() {
        CollectionCard c = new CollectionCard();
        c.setId(10L);
        c.setCardName("Lightning Bolt");
        c.setSetCode("2x2");
        c.setCardNumber("117");
        c.setCardType("Instant");
        c.setFoil(false);
        c.setLanguage("en");
        c.setQuantity(1);
        return c;
    }

    @Test
    void getById_returnsEntity() {
        when(repository.findById(10L)).thenReturn(Optional.of(existing()));
        assertThat(service.getById(10L).getCardName()).isEqualTo("Lightning Bolt");
    }

    @Test
    void getById_throwsWhenMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void update_changesMutableFields() {
        when(repository.findById(10L)).thenReturn(Optional.of(existing()));
        when(repository.save(any(CollectionCard.class))).thenAnswer(inv -> inv.getArgument(0));

        CollectionCard updated = service.update(
                10L, null, null, true, "pt", 4, null, null, null, null);

        assertThat(updated.getCardNumber()).isEqualTo("117");
        assertThat(updated.getCardType()).isEqualTo("Instant");
        assertThat(updated.isFoil()).isTrue();
        assertThat(updated.getLanguage()).isEqualTo("pt");
        assertThat(updated.getQuantity()).isEqualTo(4);
    }

    @Test
    void update_canOverrideNameAndSet() {
        when(repository.findById(10L)).thenReturn(Optional.of(existing()));
        when(repository.save(any(CollectionCard.class))).thenAnswer(inv -> inv.getArgument(0));

        CollectionCard updated = service.update(
                10L, "Bolt Alt Art", "other", false, "en", 2, null, null, null, null);

        assertThat(updated.getCardName()).isEqualTo("Bolt Alt Art");
        assertThat(updated.getSetCode()).isEqualTo("other");
    }

    @Test
    void update_rejectsInvalidArguments() {
        when(repository.findById(10L)).thenReturn(Optional.of(existing()));
        assertThatThrownBy(() -> service.update(
                10L, null, null, false, " ", 1, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(
                10L, null, null, false, "en", 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_throwsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(
                42L, null, null, false, "en", 1, null, null, null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void update_appliesCardTypePriceComentarioLocalizacao() {
        when(repository.findById(10L)).thenReturn(Optional.of(existing()));
        when(repository.save(any(CollectionCard.class))).thenAnswer(inv -> inv.getArgument(0));

        CollectionCard updated = service.update(
                10L, null, null, false, "en", 1,
                "Legendary Creature", new BigDecimal("1.23"), "mint", "Box A");

        assertThat(updated.getCardType()).isEqualTo("Legendary Creature");
        assertThat(updated.getPrice()).isEqualByComparingTo("1.23");
        assertThat(updated.getComentario()).isEqualTo("mint");
        assertThat(updated.getLocalizacao()).isEqualTo("Box A");
    }

    @Test
    void update_emptyStringsClearOptionalTextFields() {
        CollectionCard seeded = existing();
        seeded.setCardType("Instant");
        seeded.setComentario("keep");
        seeded.setLocalizacao("Box A");
        when(repository.findById(10L)).thenReturn(Optional.of(seeded));
        when(repository.save(any(CollectionCard.class))).thenAnswer(inv -> inv.getArgument(0));

        CollectionCard updated = service.update(
                10L, null, null, false, "en", 1,
                "", null, "  ", "");

        assertThat(updated.getCardType()).isNull();
        assertThat(updated.getComentario()).isNull();
        assertThat(updated.getLocalizacao()).isNull();
    }

    @Test
    void delete_returnsFalseWhenMissing() {
        when(repository.existsById(42L)).thenReturn(false);
        assertThat(service.delete(42L)).isFalse();
    }

    @Test
    void delete_deletesAndReturnsTrueWhenExists() {
        when(repository.existsById(10L)).thenReturn(true);
        assertThat(service.delete(10L)).isTrue();
        verify(repository).deleteById(10L);
    }
}
