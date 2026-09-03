package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CardImageHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CardImageHashRepository extends JpaRepository<CardImageHash, Long> {
    Optional<CardImageHash> findBySetCodeAndCollectorNumber(String setCode, String collectorNumber);

    List<CardImageHash> findBySetCode(String setCode);

    /**
     * All {@code (setCode, collectorNumber)} pairs, as a lightweight projection
     * for building an in-memory "already registered" index in one query (used by
     * the bulk download to skip existing cards without a per-card lookup).
     */
    @Query("select c.setCode, c.collectorNumber from CardImageHash c")
    List<Object[]> findAllSetCodeAndCollectorNumber();

    /**
     * Cards whose BoVW histogram hasn't been computed yet — i.e. what a
     * resumable/incremental model update still needs to process (a resumed
     * build, or a freshly downloaded edition).
     */
    List<CardImageHash> findByBovwHistogramIsNull();

    /**
     * Every distinct card name in the catalog, sorted — feeds the "Cadastro
     * Cartas" name autocomplete (client-side filtered, fetched once).
     */
    @Query("select distinct c.cardName from CardImageHash c order by c.cardName asc")
    List<String> findDistinctCardNames();

    /**
     * The set codes a given card name was printed in — used to filter the
     * "Cadastro Cartas" set dropdown down to only the sets that actually
     * have that card.
     */
    @Query("select distinct c.setCode from CardImageHash c where lower(c.cardName) = lower(:name)")
    List<String> findDistinctSetCodesByCardNameIgnoreCase(@Param("name") String name);

    /**
     * Lowest collector number for (setCode, cardName) — used to resolve an
     * image preview when the user hasn't typed a collector number yet. Most
     * (set, name) pairs have exactly one printing; picking the lowest number
     * is an arbitrary but stable tie-break when there's more than one
     * (promos, basic lands, etc).
     */
    Optional<CardImageHash> findFirstBySetCodeAndCardNameIgnoreCaseOrderByCollectorNumberAsc(
            String setCode, String cardName);
}
