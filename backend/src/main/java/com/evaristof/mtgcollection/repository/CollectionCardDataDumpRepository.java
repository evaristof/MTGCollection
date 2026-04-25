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

    /**
     * Distinct dump timestamps in ascending order within the given range.
     * Used by the price-movers calculation to find the two most recent
     * snapshots within the user's selected interval.
     */
    @Query("select distinct d.dataDumpDateTime from CollectionCardDataDump d "
            + "where d.dataDumpDateTime >= :from and d.dataDumpDateTime <= :to "
            + "order by d.dataDumpDateTime asc")
    List<LocalDateTime> findDumpTimestampsBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Aggregates each snapshot's total collection value ({@code SUM(price * quantity)})
     * within the inclusive range {@code [from, to]}. Rows with {@code NULL}
     * prices are treated as zero so a dump that only has priced cards still
     * contributes, and an unpriced dump still yields a {@code 0.00} data
     * point (which is the useful answer for the chart).
     *
     * <p>The projection uses a constructor expression so Spring Data can
     * return a typed list without the caller having to deal with
     * {@code Object[]}.</p>
     */
    @Query("""
            select new com.evaristof.mtgcollection.repository.CollectionCardDataDumpRepository$DumpTotal(
                d.dataDumpDateTime,
                coalesce(sum(coalesce(d.price, 0) * d.quantity), 0))
              from CollectionCardDataDump d
             where d.dataDumpDateTime >= :from
               and d.dataDumpDateTime <= :to
          group by d.dataDumpDateTime
          order by d.dataDumpDateTime asc
            """)
    List<DumpTotal> sumTotalValueByDumpBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Projection for {@link #sumTotalValueByDumpBetween}. Exposed as a
     * top-level record (via nested class) so JPQL's {@code new} operator
     * can instantiate it — lambdas/inner types wouldn't be visible.
     */
    record DumpTotal(LocalDateTime timestamp, java.math.BigDecimal totalValue) {
    }
}
