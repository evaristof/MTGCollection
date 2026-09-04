package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.Location;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CollectionCardRepositoryTest {

    @Autowired
    private CollectionCardRepository repository;

    @Autowired
    private LocationRepository locationRepository;

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
    @Test
    void sumValueByLocation_groupsPriceTimesQuantityPerLocation() {
        Location blue = locationRepository.save(new Location("Blue Pasta GameGenic", null));
        Location dragon = locationRepository.save(new Location("Dragon Pasta Troca", null));

        CollectionCard a = newCard("Lightning Bolt", "2x2", "117", false, "Instant", "en", 2);
        a.setPrice(new BigDecimal("1.50"));   // 3.00
        a.setLocation(blue);
        CollectionCard b = newCard("Counterspell", "neo", "51", false, "Instant", "en", 3);
        b.setPrice(new BigDecimal("2.00"));   // 6.00
        b.setLocation(blue);
        CollectionCard c = newCard("Mox Pearl", "neo", "240", true, "Artifact", "en", 1);
        c.setPrice(new BigDecimal("10.00"));  // 10.00
        c.setLocation(dragon);
        // sem preço: entra na contagem, soma zero
        CollectionCard d = newCard("Opt", "eld", "59", false, "Instant", "en", 5);
        d.setLocation(dragon);
        // sem localização: não pode sumir do relatório
        CollectionCard e = newCard("Ponder", "lrw", "76", false, "Sorcery", "en", 1);
        e.setPrice(new BigDecimal("4.00"));
        repository.saveAll(List.of(a, b, c, d, e));

        List<CollectionCardRepository.LocationValue> rows = repository.sumValueByLocation();

        assertThat(rows).hasSize(3);
        assertThat(rows).filteredOn(r -> "Blue Pasta GameGenic".equals(r.location()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.totalValue()).isEqualByComparingTo("9.00");
                    assertThat(r.totalQuantity()).isEqualTo(5L);
                    assertThat(r.cardCount()).isEqualTo(2L);
                });
        assertThat(rows).filteredOn(r -> "Dragon Pasta Troca".equals(r.location()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.totalValue()).isEqualByComparingTo("10.00");
                    assertThat(r.totalQuantity()).isEqualTo(6L);
                });
        assertThat(rows).filteredOn(r -> r.location() == null)
                .singleElement()
                .satisfies(r -> assertThat(r.totalValue()).isEqualByComparingTo("4.00"));
    }
}
