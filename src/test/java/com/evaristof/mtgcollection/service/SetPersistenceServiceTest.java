package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetPersistenceServiceTest {

    private SetService setService;
    private MagicSetRepository repository;
    private SetPersistenceService service;

    @BeforeEach
    void setUp() {
        setService = mock(SetService.class);
        repository = mock(MagicSetRepository.class);
        service = new SetPersistenceService(setService, repository);
    }

    private ScryfallSet sampleSet() {
        ScryfallSet s = new ScryfallSet();
        s.setCode("neo");
        s.setName("Kamigawa: Neon Dynasty");
        s.setReleasedAt("2022-02-18");
        s.setSetType("expansion");
        s.setCardCount(302);
        s.setPrintedSize(302);
        s.setBlockCode("NEO");
        s.setBlock("Kamigawa");
        return s;
    }

    @Test
    void toEntity_mapsAllFields() {
        MagicSet entity = SetPersistenceService.toEntity(sampleSet());

        assertThat(entity.getSetCode()).isEqualTo("neo");
        assertThat(entity.getSetName()).isEqualTo("Kamigawa: Neon Dynasty");
        assertThat(entity.getReleaseDate()).isEqualTo(LocalDate.of(2022, 2, 18));
        assertThat(entity.getSetType()).isEqualTo("expansion");
        assertThat(entity.getCardCount()).isEqualTo(302);
        assertThat(entity.getPrintedSize()).isEqualTo(302);
        assertThat(entity.getBlockCode()).isEqualTo("NEO");
        assertThat(entity.getBlockName()).isEqualTo("Kamigawa");
    }

    @Test
    void toEntity_handlesNullAndInvalidDate() {
        ScryfallSet s = new ScryfallSet();
        s.setCode("xxx");
        s.setName("Test");
        s.setReleasedAt(null);
        MagicSet entity = SetPersistenceService.toEntity(s);
        assertThat(entity.getReleaseDate()).isNull();

        s.setReleasedAt("not-a-date");
        entity = SetPersistenceService.toEntity(s);
        assertThat(entity.getReleaseDate()).isNull();
    }

    @Test
    void syncSetsFromScryfall_persistsAllSets() {
        ScryfallSet a = sampleSet();
        ScryfallSet b = new ScryfallSet();
        b.setCode("dmu");
        b.setName("Dominaria United");
        b.setReleasedAt("2022-09-09");

        when(setService.listAllSets()).thenReturn(List.of(a, b));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<MagicSet> result = service.syncSetsFromScryfall();

        assertThat(result).hasSize(2);
        ArgumentCaptor<List<MagicSet>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<MagicSet> saved = captor.getValue();
        assertThat(saved).extracting(MagicSet::getSetCode).containsExactly("neo", "dmu");
        assertThat(saved.get(0).getReleaseDate()).isEqualTo(LocalDate.of(2022, 2, 18));
        assertThat(saved.get(1).getSetName()).isEqualTo("Dominaria United");
    }

    @Test
    void syncSetsFromScryfall_handlesEmptyList() {
        when(setService.listAllSets()).thenReturn(List.of());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<MagicSet> result = service.syncSetsFromScryfall();

        assertThat(result).isEmpty();
    }
}
