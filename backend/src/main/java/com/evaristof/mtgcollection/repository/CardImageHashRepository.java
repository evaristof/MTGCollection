package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CardImageHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
