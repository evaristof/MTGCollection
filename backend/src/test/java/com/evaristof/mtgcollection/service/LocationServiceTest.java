package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationServiceTest {

    private LocationRepository repository;
    private CollectionCardRepository cardRepository;
    private LocationService service;

    @BeforeEach
    void setUp() {
        repository = mock(LocationRepository.class);
        cardRepository = mock(CollectionCardRepository.class);
        service = new LocationService(repository, cardRepository);
    }

    private static Location location(Long id, String name) {
        Location l = new Location(name, null);
        l.setId(id);
        return l;
    }

    @Test
    void findOrCreateByName_reusesExistingRowIgnoringCase() {
        Location existing = location(1L, "Caixa 3");
        when(repository.findByNameIgnoreCase("caixa 3")).thenReturn(Optional.of(existing));

        assertThat(service.findOrCreateByName("  caixa 3  ")).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void findOrCreateByName_createsTrimmedRowWhenMissing() {
        when(repository.findByNameIgnoreCase("Caixa 9")).thenReturn(Optional.empty());
        when(repository.save(any(Location.class))).thenAnswer(inv -> inv.getArgument(0));

        Location created = service.findOrCreateByName("  Caixa 9 ");

        assertThat(created.getName()).isEqualTo("Caixa 9");
    }

    @Test
    void resolve_prefersTheExplicitId() {
        Location existing = location(4L, "Binder");
        when(repository.findById(4L)).thenReturn(Optional.of(existing));

        assertThat(service.resolve(4L, "ignored")).isSameAs(existing);
        verify(repository, never()).findByNameIgnoreCase(any());
    }

    @Test
    void resolve_throwsOnUnknownId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(99L, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    @Test
    void resolve_fallsBackToNameAndCreatesIt() {
        when(repository.findByNameIgnoreCase("Caixa 1")).thenReturn(Optional.empty());
        when(repository.save(any(Location.class))).thenAnswer(inv -> inv.getArgument(0));

        Location resolved = service.resolve(null, "Caixa 1");

        assertThat(resolved.getName()).isEqualTo("Caixa 1");
    }

    @Test
    void resolve_returnsNullWhenNothingWasGiven() {
        assertThat(service.resolve(null, null)).isNull();
        assertThat(service.resolve(null, "   ")).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void countCards_delegatesToTheCardRepository() {
        when(cardRepository.countByLocation_Id(3L)).thenReturn(12L);

        assertThat(service.countCards(3L)).isEqualTo(12L);
    }
}
