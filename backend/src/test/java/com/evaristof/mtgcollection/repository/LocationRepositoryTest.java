package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.Location;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class LocationRepositoryTest {

    @Autowired
    private LocationRepository repository;

    @Test
    void saveAndFindByNameIgnoreCase() {
        repository.save(new Location("Caixa 3", "Cartas comuns em preto e branco"));

        Optional<Location> loaded = repository.findByNameIgnoreCase("caixa 3");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDescription()).isEqualTo("Cartas comuns em preto e branco");
        assertThat(repository.existsByNameIgnoreCase("CAIXA 3")).isTrue();
        assertThat(repository.existsByNameIgnoreCase("Caixa 4")).isFalse();
    }

    @Test
    void findAllByOrderByNameAsc_sortsAlphabetically() {
        repository.save(new Location("Caixa 2", null));
        repository.save(new Location("Caixa 10", null));
        repository.save(new Location("Binder Vermelho", null));

        List<Location> all = repository.findAllByOrderByNameAsc();
        assertThat(all).extracting(Location::getName)
                .containsExactly("Binder Vermelho", "Caixa 10", "Caixa 2");
    }
}
