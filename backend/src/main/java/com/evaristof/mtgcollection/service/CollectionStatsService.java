package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Números agregados da coleção <em>atual</em> (não dos snapshots), usados
 * pelos painéis da tela Gráficos.
 */
@Service
public class CollectionStatsService {

    private final CollectionCardRepository cardRepository;

    public CollectionStatsService(CollectionCardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Quanto vale hoje o que está guardado em cada localização.
     *
     * @param location       nome da localização; {@code null} para as cartas sem localização
     * @param totalValue     soma de preço × quantidade
     * @param totalQuantity  número de cópias
     * @param cardCount      número de linhas da coleção
     */
    public record LocationValue(String location, BigDecimal totalValue,
                                long totalQuantity, long cardCount) {
    }

    /** Localizações da maior para a menor soma, empatando pelo nome. */
    @Transactional(readOnly = true)
    public List<LocationValue> valueByLocation() {
        return cardRepository.sumValueByLocation().stream()
                .map(row -> new LocationValue(
                        row.location(),
                        row.totalValue() != null ? row.totalValue() : BigDecimal.ZERO,
                        row.totalQuantity() != null ? row.totalQuantity() : 0L,
                        row.cardCount() != null ? row.cardCount() : 0L))
                .sorted(Comparator
                        .comparing(LocationValue::totalValue, Comparator.reverseOrder())
                        .thenComparing(v -> v.location() == null ? "" : v.location(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
