package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CollectionCardDataDump;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CollectionCardDataDumpRepository
        extends JpaRepository<CollectionCardDataDump, Long> {

    /**
     * Distinct {@code dataDumpDateTime} values across every row, most-recent
     * first. Each value represents one snapshot the user has captured.
     */
    @Query("select distinct d.dataDumpDateTime from CollectionCardDataDump d "
            + "order by d.dataDumpDateTime desc")
    List<LocalDateTime> findDistinctDumpTimestamps();

    List<CollectionCardDataDump> findByDataDumpDateTimeOrderByCardNameAsc(LocalDateTime timestamp);

    long deleteByDataDumpDateTime(LocalDateTime timestamp);

    /**
     * Whether at least one row exists for the given timestamp. Used by the
     * controller to return 404 for unknown dumps.
     */
    boolean existsByDataDumpDateTime(LocalDateTime timestamp);

    /**
     * Convenience used by tests and future statistics work — counts the
     * rows of a single dump without loading them.
     */
    long countByDataDumpDateTime(LocalDateTime timestamp);

    /** @return the number of rows matching {@code sourceCardId} across all dumps. */
    long countBySourceCardId(@Param("sourceCardId") Long sourceCardId);
}
