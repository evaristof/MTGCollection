package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CollectionCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollectionCardRepository extends JpaRepository<CollectionCard, Long> {

    List<CollectionCard> findBySetCode(String setCode);

    Optional<CollectionCard> findBySetCodeAndCardNumberAndFoilAndLanguage(
            String setCode, String cardNumber, boolean foil, String language);

    /**
     * Candidate stacks for a card identified by (set, collector number, foil).
     *
     * <p>Language and location are compared in the service instead of here so
     * the match can be case-insensitive on language and by FK id on location
     * — the add flow merges into the stack that matches all of them and only
     * creates a new row when none does.</p>
     */
    List<CollectionCard> findAllBySetCodeAndCardNumberAndFoil(
            String setCode, String cardNumber, boolean foil);

    /**
     * Same as {@link #findAllBySetCodeAndCardNumberAndFoil} but keyed by card
     * name — the fallback for rows stored without a collector number (e.g.
     * spreadsheet imports).
     */
    List<CollectionCard> findAllBySetCodeAndCardNameIgnoreCaseAndFoil(
            String setCode, String cardName, boolean foil);

    /** How many cards point at a location — guards its deletion. */
    long countByLocation_Id(Long locationId);

    /**
     * Valor atual da coleção agrupado por localização: soma de
     * {@code preço × quantidade}, quantas cópias e quantas linhas em cada
     * uma. Cartas sem preço entram como zero (a linha continua contando nas
     * quantidades) e cartas sem localização vêm num grupo com {@code null},
     * para nenhuma carta sumir do total.
     */
    @Query("""
            select new com.evaristof.mtgcollection.repository.CollectionCardRepository$LocationValue(
                l.name,
                coalesce(sum(coalesce(c.price, 0) * c.quantity), 0),
                sum(c.quantity),
                count(c))
              from CollectionCard c
              left join c.location l
          group by l.name
            """)
    List<LocationValue> sumValueByLocation();

    /** Uma linha do agrupamento por localização; {@code location} nulo = sem localização. */
    record LocationValue(String location, java.math.BigDecimal totalValue,
                         Long totalQuantity, Long cardCount) {
    }
}
