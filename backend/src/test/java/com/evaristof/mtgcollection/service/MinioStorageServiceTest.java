package com.evaristof.mtgcollection.service;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioStorageServiceTest {

    private final MinioClient client = mock(MinioClient.class);
    private final MinioStorageService service = new MinioStorageService(client, "mtg-card-images");

    @Test
    void deleteObject_callsRemoveObjectOnBucket() throws Exception {
        service.deleteObject("Urza's Destiny - uds/75-Yawgmoth's Bargain.png");

        verify(client).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void countAllObjects_countsListing() {
        // Build the Result mocks BEFORE stubbing listObjects — nesting when()
        // inside a thenReturn() argument triggers UnfinishedStubbingException.
        List<Result<Item>> listing = List.of(
                resultOf("A - a/1-x.png"),
                resultOf("A - a/2-y.png"),
                resultOf("B - b/3-z.png"));
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(listing);

        assertThat(service.countAllObjects()).isEqualTo(3);
    }

    @Test
    void listDistinctSetFolders_returnsUniqueTopLevelFolders() {
        List<Result<Item>> listing = List.of(
                resultOf("Urza's Destiny - uds/75-Yawgmoth's Bargain.png"),
                resultOf("Urza's Destiny - uds/50-Treachery.png"),
                resultOf("Antiquities - atq/14-Transmute Artifact.png"));
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(listing);

        Set<String> folders = service.listDistinctSetFolders();

        assertThat(folders).containsExactlyInAnyOrder("Urza's Destiny - uds", "Antiquities - atq");
    }

    private static Result<Item> resultOf(String objectName) {
        @SuppressWarnings("unchecked")
        Result<Item> result = mock(Result.class);
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(objectName);
        try {
            when(result.get()).thenReturn(item);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }
}
