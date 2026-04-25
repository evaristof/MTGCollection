package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.CollectionCardDataDump;
import com.evaristof.mtgcollection.repository.CollectionCardDataDumpRepository;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages point-in-time snapshots ("data dumps") of the user's collection.
 *
 * <p>Every dump copies the entire {@code COLLECTION_CARD} table into
 * {@code COLLECTION_CARD_DATA_DUMP} with a single
 * {@code dataDumpDateTime}. Dumps are immutable after creation — editing a
 * collection row afterwards does not change the dump, which is the whole
 * point (future statistics like "biggest appreciation between two dumps"
 * depend on stable history).</p>
 */
@Service
public class CollectionCardDataDumpService {

    private final CollectionCardRepository cardRepository;
    private final CollectionCardDataDumpRepository dumpRepository;

    public CollectionCardDataDumpService(CollectionCardRepository cardRepository,
                                         CollectionCardDataDumpRepository dumpRepository) {
        this.cardRepository = cardRepository;
        this.dumpRepository = dumpRepository;
    }

    /**
     * Captures a snapshot of every row in {@code COLLECTION_CARD} using the
     * current wall clock as the dump identifier.
     *
     * <p>Timestamps are truncated to the second — the combo on the Cards
     * page serializes through ISO-8601 and the user does not care about
     * sub-second granularity. If two dumps land on the exact same second we
     * bump forward by one second so timestamps remain unique (no silent
     * merging of two captures).</p>
     *
     * @return the timestamp assigned to the new dump (also stamped on every
     *         row persisted to {@code COLLECTION_CARD_DATA_DUMP}).
     */
    @Transactional
    public LocalDateTime createDump() {
        LocalDateTime timestamp = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        while (dumpRepository.existsByDataDumpDateTime(timestamp)) {
            timestamp = timestamp.plusSeconds(1);
        }

        final LocalDateTime finalTimestamp = timestamp;
        List<CollectionCard> source = cardRepository.findAll();
        List<CollectionCardDataDump> snapshot = source.stream()
                .map(card -> CollectionCardDataDump.copyOf(card, finalTimestamp))
                .toList();
        dumpRepository.saveAll(snapshot);
        return finalTimestamp;
    }

    @Transactional(readOnly = true)
    public List<LocalDateTime> listDumpTimestamps() {
        return dumpRepository.findDistinctDumpTimestamps();
    }

    @Transactional(readOnly = true)
    public List<CollectionCardDataDump> listCardsAt(LocalDateTime timestamp) {
        return dumpRepository.findByDataDumpDateTimeOrderByCardNameAsc(timestamp);
    }

    /**
     * Deletes every row belonging to the given snapshot.
     *
     * @return {@code true} if at least one row was removed.
     */
    @Transactional
    public boolean deleteDump(LocalDateTime timestamp) {
        long removed = dumpRepository.deleteByDataDumpDateTime(timestamp);
        return removed > 0;
    }

    /**
     * Per-dump total collection value (sum of {@code price * quantity})
     * for every snapshot captured in the inclusive range
     * {@code [from, to]}. When either bound is {@code null} we substitute
     * an effectively-unbounded value so the caller can say "everything
     * since the beginning" or "everything until now".
     */
    @Transactional(readOnly = true)
    public List<CollectionCardDataDumpRepository.DumpTotal> totalValuePerDump(
            LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.of(1, 1, 1, 0, 0);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        return dumpRepository.sumTotalValueByDumpBetween(effectiveFrom, effectiveTo);
    }

    /**
     * Compares the two most recent snapshots within {@code [from, to]} and
     * returns the top {@code limit} cards that appreciated the most and the
     * top {@code limit} that depreciated the most.
     *
     * <p>A card is identified by the triple
     * {@code (cardName, setCode, foil)}. When the same identity exists in
     * both dumps we compute {@code newPrice − oldPrice}; cards that only
     * appear in one of the two dumps are ignored (they were added/removed,
     * not repriced).</p>
     *
     * @return a {@link PriceMoversResult} with both lists, or {@code null}
     *         when fewer than two dumps exist in the range.
     */
    @Transactional(readOnly = true)
    public PriceMoversResult priceMovers(LocalDateTime from, LocalDateTime to, int limit) {
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.of(1, 1, 1, 0, 0);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.of(9999, 12, 31, 23, 59, 59);

        List<LocalDateTime> timestamps = dumpRepository.findDumpTimestampsBetween(effectiveFrom, effectiveTo);
        if (timestamps.size() < 2) {
            return null;
        }

        LocalDateTime oldTs = timestamps.get(timestamps.size() - 2);
        LocalDateTime newTs = timestamps.get(timestamps.size() - 1);

        List<CollectionCardDataDump> oldRows = dumpRepository.findByDataDumpDateTimeOrderByCardNameAsc(oldTs);
        List<CollectionCardDataDump> newRows = dumpRepository.findByDataDumpDateTimeOrderByCardNameAsc(newTs);

        Map<CardKey, CollectionCardDataDump> oldMap = new HashMap<>();
        for (CollectionCardDataDump d : oldRows) {
            oldMap.put(new CardKey(d.getCardName(), d.getSetCode(), d.isFoil(), d.getLanguage()), d);
        }

        List<CardMover> movers = new ArrayList<>();
        for (CollectionCardDataDump nd : newRows) {
            CardKey key = new CardKey(nd.getCardName(), nd.getSetCode(), nd.isFoil(), nd.getLanguage());
            CollectionCardDataDump od = oldMap.get(key);
            if (od == null) continue;

            BigDecimal oldPrice = od.getPrice() != null ? od.getPrice() : BigDecimal.ZERO;
            BigDecimal newPrice = nd.getPrice() != null ? nd.getPrice() : BigDecimal.ZERO;
            BigDecimal diff = newPrice.subtract(oldPrice);
            if (diff.signum() == 0) continue;

            movers.add(new CardMover(
                    nd.getCardName(),
                    nd.getSetCode(),
                    nd.getSetNameRaw(),
                    nd.isFoil(),
                    nd.getSourceCardId(),
                    oldPrice,
                    newPrice,
                    diff));
        }

        List<CardMover> gainers = movers.stream()
                .filter(m -> m.priceDiff().signum() > 0)
                .sorted(Comparator.comparing(CardMover::priceDiff).reversed())
                .limit(limit)
                .toList();

        List<CardMover> losers = movers.stream()
                .filter(m -> m.priceDiff().signum() < 0)
                .sorted(Comparator.comparing(CardMover::priceDiff))
                .limit(limit)
                .toList();

        return new PriceMoversResult(oldTs, newTs, gainers, losers);
    }

    private record CardKey(String cardName, String setCode, boolean foil, String language) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CardKey that)) return false;
            return foil == that.foil
                    && Objects.equals(cardName, that.cardName)
                    && Objects.equals(setCode, that.setCode)
                    && Objects.equals(language, that.language);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cardName, setCode, foil, language);
        }
    }

    public record CardMover(
            String cardName,
            String setCode,
            String setNameRaw,
            boolean foil,
            Long sourceCardId,
            BigDecimal priceOld,
            BigDecimal priceNew,
            BigDecimal priceDiff) {
    }

    public record PriceMoversResult(
            LocalDateTime oldTimestamp,
            LocalDateTime newTimestamp,
            List<CardMover> gainers,
            List<CardMover> losers) {
    }
}
