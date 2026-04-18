package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.CollectionCardDataDump;
import com.evaristof.mtgcollection.repository.CollectionCardDataDumpRepository;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level tests for {@link CollectionCardDataDumpService}. We use
 * {@code @DataJpaTest} with an H2 in-memory DB so the tests exercise the
 * real repository methods ({@code findDistinctDumpTimestamps},
 * {@code deleteByDataDumpDateTime}, etc.).
 */
@DataJpaTest
@Import(CollectionCardDataDumpService.class)
class CollectionCardDataDumpServiceTest {

    @Autowired
    private CollectionCardRepository cardRepository;

    @Autowired
    private CollectionCardDataDumpRepository dumpRepository;

    @Autowired
    private CollectionCardDataDumpService service;

    @BeforeEach
    void seedCollection() {
        cardRepository.save(buildCard("Treachery", "uds", "39", true, new BigDecimal("400.00")));
        cardRepository.save(buildCard("Lightning Bolt", "m11", "146", false, new BigDecimal("1.50")));
        cardRepository.save(buildCard("Ancient Tomb", "uma", "236", false, new BigDecimal("127.91")));
    }

    @Test
    void createDump_snapshotsEveryCollectionCardWithSharedTimestamp() {
        LocalDateTime stamp = service.createDump();

        List<CollectionCardDataDump> rows = service.listCardsAt(stamp);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.getDataDumpDateTime()).isEqualTo(stamp));
        assertThat(rows).extracting(CollectionCardDataDump::getCardName)
                .containsExactlyInAnyOrder("Treachery", "Lightning Bolt", "Ancient Tomb");
    }

    @Test
    void createDump_twoCallsInSameSecondYieldTwoDistinctTimestamps() {
        LocalDateTime first = service.createDump();
        LocalDateTime second = service.createDump();

        assertThat(second).isAfter(first);
        assertThat(service.listDumpTimestamps()).containsExactly(second, first);
    }

    @Test
    void createDump_modifyingCollectionAfterwardsDoesNotAffectDump() {
        LocalDateTime stamp = service.createDump();

        CollectionCard treachery = cardRepository.findAll().stream()
                .filter(c -> "Treachery".equals(c.getCardName()))
                .findFirst().orElseThrow();
        treachery.setPrice(new BigDecimal("999.99"));
        cardRepository.save(treachery);

        BigDecimal dumpedPrice = service.listCardsAt(stamp).stream()
                .filter(d -> "Treachery".equals(d.getCardName()))
                .map(CollectionCardDataDump::getPrice)
                .findFirst().orElseThrow();
        assertThat(dumpedPrice).isEqualByComparingTo("400.00");
    }

    @Test
    void deleteDump_removesOnlyTheSelectedSnapshot() {
        LocalDateTime a = service.createDump();
        LocalDateTime b = service.createDump();
        assertThat(service.listDumpTimestamps()).hasSize(2);

        assertThat(service.deleteDump(a)).isTrue();
        assertThat(service.listDumpTimestamps()).containsExactly(b);
        assertThat(service.listCardsAt(a)).isEmpty();
        assertThat(service.listCardsAt(b)).hasSize(3);
    }

    @Test
    void deleteDump_unknownTimestampReturnsFalse() {
        assertThat(service.deleteDump(LocalDateTime.of(1999, 1, 1, 0, 0))).isFalse();
    }

    @Test
    void listDumpTimestamps_returnsMostRecentFirst() {
        LocalDateTime first = service.createDump();
        LocalDateTime second = service.createDump();
        LocalDateTime third = service.createDump();

        assertThat(service.listDumpTimestamps()).containsExactly(third, second, first);
    }

    @Test
    void createDump_onEmptyCollectionPersistsNoRowsAndProducesNoDump() {
        cardRepository.deleteAll();
        LocalDateTime stamp = service.createDump();
        // Empty snapshot: the dump is not visible because there are no rows
        // — this is fine; the Cards page only offers to load a dump that
        // has rows (listDumpTimestamps is derived from the rows themselves).
        assertThat(dumpRepository.countByDataDumpDateTime(stamp)).isZero();
        assertThat(service.listDumpTimestamps()).isEmpty();
    }

    private static CollectionCard buildCard(String name, String setCode, String number,
                                            boolean foil, BigDecimal price) {
        CollectionCard card = new CollectionCard();
        card.setCardName(name);
        card.setSetCode(setCode);
        card.setCardNumber(number);
        card.setFoil(foil);
        card.setLanguage("English");
        card.setQuantity(1);
        card.setPrice(price);
        return card;
    }
}
