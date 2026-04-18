package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.CollectionCardDataDump;
import com.evaristof.mtgcollection.repository.CollectionCardDataDumpRepository;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
}
