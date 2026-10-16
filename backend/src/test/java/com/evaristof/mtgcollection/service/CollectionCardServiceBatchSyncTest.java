package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CollectionCardService#syncCardsBatch(DataJob, List)} — the
 * batched refresh wired up to the "Sincronizar selecionados" button, which
 * replaces N sequential {@code syncCard} calls with one or two
 * {@code POST /cards/collection} round trips.
 */
class CollectionCardServiceBatchSyncTest {

    private CollectionCardRepository repository;
    private CardBatchLookupService batchLookup;
    private CollectionCardService service;

    @BeforeEach
    void setUp() {
        repository = mock(CollectionCardRepository.class);
        batchLookup = mock(CardBatchLookupService.class);
        service = new CollectionCardService(mock(CardLookupService.class), repository, batchLookup, 0L);
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CollectionCard row(Long id, String name, String set, String number, boolean foil) {
        CollectionCard c = new CollectionCard();
        c.setId(id);
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
    void syncCardsBatch_updatesMultipleRowsFromOneBatchCall() {
        CollectionCard r1 = row(1L, "Lightning Bolt", "2x2", "117", false);
        CollectionCard r2 = row(2L, "Thopter", "tkld", "7", false);
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(r1, r2));
        when(batchLookup.getCardsBatch(any(), anyLong(), any())).thenReturn(List.of(
                scryfall("Lightning Bolt", "2x2", "117", "Instant", "0.50", null),
                scryfall("Thopter", "tkld", "7", "Token Artifact Creature — Thopter", "0.08", null)));

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L, 2L));

        assertThat(r1.getPrice()).isEqualByComparingTo("0.50");
        assertThat(r2.getPrice()).isEqualByComparingTo("0.08");
        assertThat(job.getTotal()).isEqualTo(2);
        assertThat(job.getProcessed()).isEqualTo(2);
        assertThat(job.getSucceeded()).isEqualTo(2);
        assertThat(job.getErrors()).isEmpty();
        verify(repository).saveAll(List.of(r1, r2));
    }

    @Test
    void syncCardsBatch_matchesRowsWithoutACollectorNumberByName() {
        CollectionCard r1 = row(1L, "Treachery", "ulg", null, false);
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(r1));
        when(batchLookup.getCardsBatch(any(), anyLong(), any()))
                .thenReturn(List.of(scryfall("Treachery", "ulg", "23", "Enchantment — Aura", "250.00", null)));

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L));

        assertThat(r1.getPrice()).isEqualByComparingTo("250.00");
        // backfilled because it was blank, same as syncCard(Long)
        assertThat(r1.getCardNumber()).isEqualTo("23");
    }

    @Test
    void syncCardsBatch_recordsAnErrorWhenScryfallHasNoMatch() {
        CollectionCard r1 = row(1L, "Carta Inexistente", "xyz", "1", false);
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(r1));
        when(batchLookup.getCardsBatch(any(), anyLong(), any())).thenReturn(List.of());

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L));

        assertThat(job.getProcessed()).isEqualTo(1);
        assertThat(job.getSucceeded()).isEqualTo(0);
        assertThat(job.getErrors()).hasSize(1);
        assertThat(job.getErrors().get(0)).contains("#1").contains("Carta Inexistente");
        verify(repository).saveAll(List.of());
    }

    @Test
    void syncCardsBatch_recordsAnErrorWhenCardHasNoPriceButStillSavesTheType() {
        CollectionCard r1 = row(1L, "X", "set", "1", false);
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(r1));
        when(batchLookup.getCardsBatch(any(), anyLong(), any()))
                .thenReturn(List.of(scryfall("X", "set", "1", "Instant", null, null)));

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L));

        assertThat(r1.getCardType()).isEqualTo("Instant");
        assertThat(r1.getPrice()).isNull();
        assertThat(job.getSucceeded()).isEqualTo(0);
        assertThat(job.getErrors()).hasSize(1);
        verify(repository).saveAll(List.of(r1));
    }

    @Test
    void syncCardsBatch_foilRowWithoutUsdFoil_retriesTheStarredPrintInASecondBatchCall() {
        // Old-core-set case (e.g. City of Brass 7ED #327 / #327★): the plain
        // print the first batch returns has no foil price at all.
        CollectionCard r1 = row(1L, "City of Brass", "7ed", "327", true);
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(r1));

        ScryfallCard plain = scryfall("City of Brass", "7ed", "327", "Land", "4.00", null);
        ScryfallCard starred = scryfall("City of Brass", "7ed", "327★", "Land", "4.00", "35.00");

        when(batchLookup.getCardsBatch(any(), anyLong(), any())).thenReturn(List.of(plain));
        when(batchLookup.getCardsBatch(any(), anyLong(), isNull())).thenReturn(List.of(starred));

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L));

        assertThat(r1.getPrice()).isEqualByComparingTo("35.00");
        // the row's own card_number stays the plain one — only the price/type
        // source is the starred print, same as syncCard(Long)/preferFoilPrintIfNeeded.
        assertThat(r1.getCardNumber()).isEqualTo("327");
        assertThat(job.getSucceeded()).isEqualTo(1);
        assertThat(job.getErrors()).isEmpty();

        ArgumentCaptorHelper.verifySecondCallUsedStarredIdentifier(batchLookup);
    }

    @Test
    void syncCardsBatch_doesNotRetryStarredPrintWhenPlainPrintAlreadyHasAFoilPrice() {
        CollectionCard r1 = row(1L, "Sol Ring", "cmr", "1", true);
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(r1));
        when(batchLookup.getCardsBatch(any(), anyLong(), any()))
                .thenReturn(List.of(scryfall("Sol Ring", "cmr", "1", "Artifact", "1.00", "7.25")));

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L));

        assertThat(r1.getPrice()).isEqualByComparingTo("7.25");
        // only the one lookup call — no starred retry needed
        verify(batchLookup, org.mockito.Mockito.times(1)).getCardsBatch(any(), anyLong(), any());
    }

    @Test
    void syncCardsBatch_scryfallFailure_recordsAnErrorPerRowAndSavesNothing() {
        CollectionCard r1 = row(1L, "X", "set", "1", false);
        CollectionCard r2 = row(2L, "Y", "set", "2", false);
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(r1, r2));
        when(batchLookup.getCardsBatch(any(), anyLong(), any()))
                .thenThrow(new ScryfallLookupException(
                        "https://api.scryfall.com/cards/collection", "HTTP 502", null));

        DataJob job = new DataJob("sync-collection-cards");
        service.syncCardsBatch(job, List.of(1L, 2L));

        assertThat(job.getProcessed()).isEqualTo(2);
        assertThat(job.getSucceeded()).isEqualTo(0);
        assertThat(job.getErrors()).hasSize(2);
        verify(repository, org.mockito.Mockito.never()).saveAll(any());
    }

    /** Small helper kept local to this test to avoid an extra top-level class. */
    private static final class ArgumentCaptorHelper {
        static void verifySecondCallUsedStarredIdentifier(CardBatchLookupService batchLookup) {
            org.mockito.ArgumentCaptor<List<ScryfallCardIdentifier>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(batchLookup, org.mockito.Mockito.times(2))
                    .getCardsBatch(captor.capture(), anyLong(), any());
            List<ScryfallCardIdentifier> secondCallIdentifiers = captor.getAllValues().get(1);
            assertThat(secondCallIdentifiers).hasSize(1);
            assertThat(secondCallIdentifiers.get(0).getCollectorNumber()).isEqualTo("327★");
        }
    }
}
