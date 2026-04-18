package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.MagicSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class MagicSetRepositoryTest {

    @Autowired
    private MagicSetRepository repository;

    @Test
    void saveAndFindById() {
        MagicSet set = new MagicSet(
                "neo",
                "Kamigawa: Neon Dynasty",
                LocalDate.of(2022, 2, 18),
                "expansion",
                302,
                302,
                "NEO",
                "Kamigawa"
        );

        repository.save(set);

        Optional<MagicSet> loaded = repository.findById("neo");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSetName()).isEqualTo("Kamigawa: Neon Dynasty");
        assertThat(loaded.get().getReleaseDate()).isEqualTo(LocalDate.of(2022, 2, 18));
        assertThat(loaded.get().getBlockName()).isEqualTo("Kamigawa");
    }
}
