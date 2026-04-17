package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CollectionCard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CollectionCardRepositoryTest {

    @Autowired
    private CollectionCardRepository repository;

    private static CollectionCard newCard(String name, String set, String number,
                                          boolean foil, String type, String lang, int qty) {
        CollectionCard c = new CollectionCard();
        c.setCardName(name);
        c.setSetCode(set);
        c.setCardNumber(number);
        c.setFoil(foil);
        c.setCardType(type);
        c.setLanguage(lang);
        c.setQuantity(qty);
        return c;
    }

    @Test
    void saveAndReload() {
        CollectionCard saved = repository.save(
                newCard("Lightning Bolt", "2x2", "117", true, "Instant", "en", 4));

        assertThat(saved.getId()).isNotNull();
        Optional<CollectionCard> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getCardName()).isEqualTo("Lightning Bolt");
        assertThat(loaded.get().getSetCode()).isEqualTo("2x2");
        assertThat(loaded.get().getCardNumber()).isEqualTo("117");
        assertThat(loaded.get().getCardType()).isEqualTo("Instant");
        assertThat(loaded.get().isFoil()).isTrue();
        assertThat(loaded.get().getLanguage()).isEqualTo("en");
        assertThat(loaded.get().getQuantity()).isEqualTo(4);
    }

    @Test
    void findBySetCodeReturnsOnlyMatchingSet() {
        repository.save(newCard("Lightning Bolt", "2x2", "117", false, "Instant", "en", 1));
        repository.save(newCard("Counterspell", "neo", "51", false, "Instant", "en", 2));
        repository.save(newCard("Mox Pearl", "neo", "240", true, "Artifact", "en", 1));

        List<CollectionCard> neoCards = repository.findBySetCode("neo");
        assertThat(neoCards).hasSize(2)
                .extracting(CollectionCard::getCardName)
                .containsExactlyInAnyOrder("Counterspell", "Mox Pearl");
    }

    @Test
    void findByCompositeKeyReturnsExactStack() {
        repository.save(newCard("Lightning Bolt", "2x2", "117", false, "Instant", "en", 1));
        repository.save(newCard("Lightning Bolt", "2x2", "117", true, "Instant", "en", 2));
        repository.save(newCard("Lightning Bolt", "2x2", "117", true, "Instant", "pt", 3));

        Optional<CollectionCard> match = repository
                .findBySetCodeAndCardNumberAndFoilAndLanguage("2x2", "117", true, "pt");
        assertThat(match).isPresent();
        assertThat(match.get().getQuantity()).isEqualTo(3);
    }
}
