package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionStatsServiceTest {

    private CollectionCardRepository repository;
    private CollectionStatsService service;

    @BeforeEach
    void setUp() {
        repository = mock(CollectionCardRepository.class);
        service = new CollectionStatsService(repository);
    }

    private static CollectionCardRepository.LocationValue row(String location, String value,
                                                              Long quantity, Long cards) {
        return new CollectionCardRepository.LocationValue(
                location, value == null ? null : new BigDecimal(value), quantity, cards);
    }

    @Test
    void ordersLocationsByValueDescending() {
        when(repository.sumValueByLocation()).thenReturn(List.of(
                row("Caixa 1", "10.00", 3L, 2L),
                row("Dragon Pasta Troca", "250.50", 40L, 30L),
                row("Blue Pasta GameGenic", "99.00", 12L, 10L)));

        assertThat(service.valueByLocation())
                .extracting(CollectionStatsService.LocationValue::location)
                .containsExactly("Dragon Pasta Troca", "Blue Pasta GameGenic", "Caixa 1");
    }

    @Test
    void keepsCardsWithoutLocationAndWithoutPrice() {
        when(repository.sumValueByLocation()).thenReturn(List.of(
                row(null, "4.00", 1L, 1L),
                // localização só com cartas sem preço: soma zero, mas continua na lista
                row("Caixa vazia", null, 5L, 5L)));

        List<CollectionStatsService.LocationValue> rows = service.valueByLocation();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).location()).isNull();
        assertThat(rows.get(0).totalValue()).isEqualByComparingTo("4.00");
        assertThat(rows.get(1).location()).isEqualTo("Caixa vazia");
        assertThat(rows.get(1).totalValue()).isEqualByComparingTo("0");
        assertThat(rows.get(1).totalQuantity()).isEqualTo(5L);
    }

    @Test
    void emptyCollectionYieldsNoRows() {
        when(repository.sumValueByLocation()).thenReturn(List.of());

        assertThat(service.valueByLocation()).isEmpty();
    }
}
