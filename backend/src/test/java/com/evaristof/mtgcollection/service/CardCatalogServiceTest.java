package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CardImageHashRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardCatalogServiceTest {

    @Mock
    private CardImageHashRepository cardImageHashRepository;

    @Mock
    private MagicSetRepository magicSetRepository;

    private CardCatalogService service;

    @BeforeEach
    void setUp() {
        service = new CardCatalogService(cardImageHashRepository, magicSetRepository);
    }

    private static CardImageHash hash(String set, String number, String name) {
        CardImageHash h = new CardImageHash();
        h.setSetCode(set);
        h.setCollectorNumber(number);
        h.setCardName(name);
        h.setPHash("hash");
        h.setMinioPath("path");
        return h;
    }

    @Test
    void allCardNames_delegatesToRepository() {
        when(cardImageHashRepository.findDistinctCardNames()).thenReturn(List.of("Lightning Bolt", "Sol Ring"));

        assertThat(service.allCardNames()).containsExactly("Lightning Bolt", "Sol Ring");
    }

    @Test
    void setsForCardName_usesSetNameWhenKnown_fallsBackToCodeOtherwise() {
        when(cardImageHashRepository.findDistinctSetCodesByCardNameIgnoreCase("Sol Ring"))
                .thenReturn(List.of("cmr", "unk"));
        when(magicSetRepository.findById("cmr"))
                .thenReturn(Optional.of(new MagicSet("cmr", "Commander Legends", null, null, null, null, null, null, null)));
        when(magicSetRepository.findById("unk")).thenReturn(Optional.empty());

        List<CardCatalogService.SetOption> options = service.setsForCardName("Sol Ring");

        assertThat(options).extracting(CardCatalogService.SetOption::setCode)
                .containsExactlyInAnyOrder("cmr", "unk");
        assertThat(options).filteredOn(o -> o.setCode().equals("cmr"))
                .extracting(CardCatalogService.SetOption::setName)
                .containsExactly("Commander Legends");
        assertThat(options).filteredOn(o -> o.setCode().equals("unk"))
                .extracting(CardCatalogService.SetOption::setName)
                .containsExactly("unk");
    }

    @Test
    void setsForCardName_blankName_returnsEmpty() {
        assertThat(service.setsForCardName(" ")).isEmpty();
    }

    @Test
    void lookupCardName_foundAndNotFound() {
        when(cardImageHashRepository.findBySetCodeAndCollectorNumber("neo", "123"))
                .thenReturn(Optional.of(hash("neo", "123", "Boseiju, Who Endures")));
        when(cardImageHashRepository.findBySetCodeAndCollectorNumber("neo", "999"))
                .thenReturn(Optional.empty());

        assertThat(service.lookupCardName("neo", "123")).contains("Boseiju, Who Endures");
        assertThat(service.lookupCardName("neo", "999")).isEmpty();
    }

    @Test
    void resolveCollectorNumber_delegatesToRepository() {
        when(cardImageHashRepository
                .findFirstBySetCodeAndCardNameIgnoreCaseOrderByCollectorNumberAsc("neo", "Boseiju, Who Endures"))
                .thenReturn(Optional.of(hash("neo", "123", "Boseiju, Who Endures")));

        assertThat(service.resolveCollectorNumber("neo", "Boseiju, Who Endures")).contains("123");
    }
}
