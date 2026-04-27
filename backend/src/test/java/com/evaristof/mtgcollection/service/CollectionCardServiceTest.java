package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionCardServiceTest {

    private CardLookupService cardLookupService;
    private CollectionCardRepository repository;
    private CollectionCardService service;

    @BeforeEach
    void setUp() {
        cardLookupService = mock(CardLookupService.class);
        repository = mock(CollectionCardRepository.class);
        service = new CollectionCardService(cardLookupService, repository);
    }

    private static ScryfallCard card(String name, String set, String number, String typeLine) {
        ScryfallCard c = new ScryfallCard();
        c.setName(name);
        c.setSet(set);
        c.setCollectorNumber(number);
        c.setTypeLine(typeLine);
        return c;
    }

    @Test
    void addCardToCollection_populatesFieldsFromScryfallAndCaller() {
        when(cardLookupService.getCardByNameAndSet("Lightning Bolt", "2x2"))
                .thenReturn(card("Lightning Bolt", "2x2", "117", "Instant"));
        when(repository.save(org.mockito.ArgumentMatchers.any(CollectionCard.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CollectionCard saved = service.addCardToCollection("Lightning Bolt", "2x2", true, "en", 3);

        ArgumentCaptor<CollectionCard> captor = ArgumentCaptor.forClass(CollectionCard.class);
        verify(repository).save(captor.capture());
        CollectionCard persisted = captor.getValue();

        assertThat(persisted.getCardName()).isEqualTo("Lightning Bolt");
        assertThat(persisted.getSetCode()).isEqualTo("2x2");
        assertThat(persisted.getCardNumber()).isEqualTo("117");
        assertThat(persisted.getCardType()).isEqualTo("Instant");
        assertThat(persisted.isFoil()).isTrue();
        assertThat(persisted.getLanguage()).isEqualTo("en");
        assertThat(persisted.getQuantity()).isEqualTo(3);

        assertThat(saved).isSameAs(persisted);
    }

    @Test
    void addCardToCollection_fallsBackToCallerInputsWhenScryfallMissingFields() {
        ScryfallCard partial = new ScryfallCard();
        partial.setCollectorNumber("50");
        partial.setTypeLine("Creature — Elf");
        when(cardLookupService.getCardByNameAndSet("Llanowar Elves", "m11"))
                .thenReturn(partial);
        when(repository.save(org.mockito.ArgumentMatchers.any(CollectionCard.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.addCardToCollection("Llanowar Elves", "m11", false, "pt", 1);

        ArgumentCaptor<CollectionCard> captor = ArgumentCaptor.forClass(CollectionCard.class);
        verify(repository).save(captor.capture());
        CollectionCard persisted = captor.getValue();
        assertThat(persisted.getCardName()).isEqualTo("Llanowar Elves");
        assertThat(persisted.getSetCode()).isEqualTo("m11");
        assertThat(persisted.getCardNumber()).isEqualTo("50");
        assertThat(persisted.getCardType()).isEqualTo("Creature — Elf");
        assertThat(persisted.isFoil()).isFalse();
        assertThat(persisted.getLanguage()).isEqualTo("pt");
        assertThat(persisted.getQuantity()).isEqualTo(1);
    }

    @Test
    void addCardToCollection_rejectsInvalidArguments() {
        assertThatThrownBy(() -> service.addCardToCollection("", "neo", false, "en", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addCardToCollection("x", "", false, "en", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addCardToCollection("x", "neo", false, " ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addCardToCollection("x", "neo", false, "en", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addCardToCollection("x", "neo", false, "en", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addCardToCollection_throwsWhenScryfallReturnsNull() {
        when(cardLookupService.getCardByNameAndSet("ghost", "neo")).thenReturn(null);

        assertThatThrownBy(() -> service.addCardToCollection("ghost", "neo", false, "en", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listAllAndListBySet_delegateToRepository() {
        CollectionCard a = new CollectionCard();
        CollectionCard b = new CollectionCard();
        when(repository.findAll()).thenReturn(List.of(a, b));
        when(repository.findBySetCode("neo")).thenReturn(List.of(a));

        assertThat(service.listAll()).containsExactly(a, b);
        assertThat(service.listBySet("neo")).containsExactly(a);
    }
}
