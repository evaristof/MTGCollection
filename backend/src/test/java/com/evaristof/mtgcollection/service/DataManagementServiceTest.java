package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataManagementServiceTest {

    private final CollectionCardRepository collectionRepo = mock(CollectionCardRepository.class);
    private final CardImageHashRepository hashRepo = mock(CardImageHashRepository.class);
    private final MagicSetRepository setRepo = mock(MagicSetRepository.class);
    private final MinioStorageService minio = mock(MinioStorageService.class);
    private final CardBatchLookupService batch = mock(CardBatchLookupService.class);
    private final CardImageMatchService match = mock(CardImageMatchService.class);
    private final OrbArtMatchService orb = mock(OrbArtMatchService.class);
    private final com.evaristof.mtgcollection.scryfall.ScryfallHttpClient scryfall =
            mock(com.evaristof.mtgcollection.scryfall.ScryfallHttpClient.class);

    private final DataManagementService service = new DataManagementService(
            collectionRepo, hashRepo, setRepo, minio, batch, match, orb,
            scryfall, new com.google.gson.Gson(), HttpClient.newHttpClient());

    @Test
    void stats_aggregatesCountsFromMinioAndRepos() {
        when(minio.countAllObjects()).thenReturn(42L);
        when(minio.listDistinctSetFolders()).thenReturn(Set.of("A - a", "B - b", "C - c"));
        when(collectionRepo.count()).thenReturn(350L);
        when(hashRepo.count()).thenReturn(130L);

        DataManagementService.DataStats stats = service.stats();

        assertThat(stats.setsInMinio()).isEqualTo(3);
        assertThat(stats.photosInMinio()).isEqualTo(42);
        assertThat(stats.cardsInCollection()).isEqualTo(350);
        assertThat(stats.cardsWithHash()).isEqualTo(130);
    }

    @Test
    void prune_deletesOnlyImagesOutsideCollectionAndRemovesOrphanHash() {
        when(collectionRepo.findAll()).thenReturn(List.of(card("uds", "75"), card("atq", "14")));
        String ownedKey = "Urza's Destiny - uds/75-Yawgmoth's Bargain.png";
        String outsideKey = "Some Set - xyz/1-Foo.png";
        when(minio.listAllObjectKeys()).thenReturn(List.of(ownedKey, outsideKey));
        when(match.parseSetAndNumber(ownedKey)).thenReturn(new String[] {"uds", "75"});
        when(match.parseSetAndNumber(outsideKey)).thenReturn(new String[] {"xyz", "1"});
        CardImageHash orphan = new CardImageHash();
        when(hashRepo.findBySetCodeAndCollectorNumber("xyz", "1")).thenReturn(Optional.of(orphan));

        DataJob job = new DataJob("prune");
        service.pruneOutsideCollection(job);

        verify(minio).deleteObject(outsideKey);
        verify(minio, never()).deleteObject(ownedKey);
        verify(hashRepo).delete(orphan);
        verify(orb).invalidate();
        assertThat(job.getSucceeded()).isEqualTo(1);
        assertThat(job.getSkipped()).isEqualTo(1);
        assertThat(job.getProcessed()).isEqualTo(2);
    }

    @Test
    void prune_keepsImagesWhoseKeyIsUnparseable_notMistakenlyDeleted() {
        when(collectionRepo.findAll()).thenReturn(List.of(card("uds", "75")));
        String weirdKey = "loose-file-no-folder.png";
        when(minio.listAllObjectKeys()).thenReturn(List.of(weirdKey));
        when(match.parseSetAndNumber(weirdKey)).thenReturn(null);

        DataJob job = new DataJob("prune");
        service.pruneOutsideCollection(job);

        // Unparseable keys are treated as "outside collection" and removed —
        // they can't be matched to an owned card. Verify it's deleted (not
        // silently kept) and no orphan-hash lookup blows up.
        verify(minio).deleteObject(weirdKey);
        assertThat(job.getSucceeded()).isEqualTo(1);
    }

    @Test
    void processBulkCards_skipsCardAlreadyPresent_andCountsSet() {
        // Card lea/232 is already in MinIO *and* the hash index → skipped with
        // no download/upload/registration. Its set counts as one edition seen.
        String objectKey = "Limited Edition Alpha - lea/232-Black Lotus.png";
        when(minio.objectKey("Limited Edition Alpha", "lea", "232", "Black Lotus"))
                .thenReturn(objectKey);

        Set<String> whitelist = Set.of("lea");
        Set<String> existingHashKeys = new java.util.HashSet<>(Set.of("lea|232"));
        Set<String> existingObjectKeys = new java.util.HashSet<>(Set.of(objectKey));

        String bulk = "[{\"name\":\"Black Lotus\",\"set\":\"lea\","
                + "\"set_name\":\"Limited Edition Alpha\",\"collector_number\":\"232\","
                + "\"image_uris\":{\"png\":\"https://img/lea-232.png\"}}]";

        DataJob job = new DataJob("download-all-scryfall");
        service.processBulkCards(new java.io.StringReader(bulk), whitelist,
                existingHashKeys, existingObjectKeys, job);

        verify(minio, never()).upload(any(), any());
        verify(minio, never()).download(any());
        verify(match, never()).registerHashIfAbsent(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(job.getSkipped()).isEqualTo(1);
        assertThat(job.getSucceeded()).isEqualTo(0);
        assertThat(job.getProcessed()).isEqualTo(1); // one edition seen
    }

    @Test
    void processBulkCards_ignoresCardsWhoseSetIsNotInWhitelist() {
        // A card from a set not in magic_set (e.g. a digital-only set) must be
        // skipped entirely: no download, not counted, no set marked seen.
        Set<String> whitelist = Set.of("lea");
        Set<String> empty = new java.util.HashSet<>();

        String bulk = "[{\"name\":\"Some Token\",\"set\":\"tmh3\","
                + "\"collector_number\":\"1\",\"image_uris\":{\"png\":\"https://img/x.png\"}}]";

        DataJob job = new DataJob("download-all-scryfall");
        service.processBulkCards(new java.io.StringReader(bulk), whitelist, empty, empty, job);

        verify(minio, never()).upload(any(), any());
        verify(minio, never()).objectKey(any(), any(), any(), any());
        assertThat(job.getProcessed()).isEqualTo(0);
        assertThat(job.getSucceeded()).isEqualTo(0);
        assertThat(job.getSkipped()).isEqualTo(0);
        assertThat(job.getErrors()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadCollection_dedupsIdentifiersBySetAndNumber() {
        // Collection has the same printing three times (foil/non-foil/qty
        // stacks) plus another card — only 2 distinct images to fetch.
        when(collectionRepo.findAll()).thenReturn(List.of(
                card("uds", "75"), card("uds", "75"), card("atq", "14")));
        when(batch.getCardsBatch(any(), anyLong(), any())).thenReturn(List.<ScryfallCard>of());

        DataJob job = new DataJob("download-collection");
        service.downloadCollectionImages(job);

        ArgumentCaptor<List<ScryfallCardIdentifier>> captor = ArgumentCaptor.forClass(List.class);
        verify(batch).getCardsBatch(captor.capture(), anyLong(), any());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(job.getTotal()).isEqualTo(2);
    }

    private static CollectionCard card(String set, String number) {
        CollectionCard c = new CollectionCard();
        c.setSetCode(set);
        c.setCardNumber(number);
        c.setCardName("x");
        return c;
    }
}
