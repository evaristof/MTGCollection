package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that fetches the full set list from Scryfall (via {@link SetService})
 * and persists each set to the in-memory database as a {@link MagicSet}.
 */
@Service
public class SetPersistenceService {

    private final SetService setService;
    private final MagicSetRepository repository;
    private final SetImageService setImageService;

    public SetPersistenceService(SetService setService, MagicSetRepository repository,
                                 SetImageService setImageService) {
        this.setService = setService;
        this.repository = repository;
        this.setImageService = setImageService;
    }

    /**
     * Fetches all Magic sets from Scryfall and persists them to the database.
     * Existing rows with the same SET_CODE are overwritten (save-all upsert).
     * After persisting, downloads set icons to MinIO in the background.
     *
     * @return the list of persisted {@link MagicSet} entities
     */
    @Transactional
    public List<MagicSet> syncSetsFromScryfall() {
        List<ScryfallSet> scryfallSets = setService.listAllSets();
        List<MagicSet> entities = new ArrayList<>(scryfallSets.size());
        for (ScryfallSet s : scryfallSets) {
            entities.add(toEntity(s));
        }
        List<MagicSet> saved = repository.saveAll(entities);
        try {
            setImageService.syncAllSetIcons();
        } catch (RuntimeException e) {
            // icon sync is best-effort; don't fail the set sync
        }
        return saved;
    }

    static MagicSet toEntity(ScryfallSet s) {
        MagicSet entity = new MagicSet();
        entity.setSetCode(s.getCode());
        entity.setSetName(s.getName());
        entity.setReleaseDate(parseDate(s.getReleasedAt()));
        entity.setSetType(s.getSetType());
        entity.setCardCount(s.getCardCount());
        entity.setPrintedSize(s.getPrintedSize());
        entity.setBlockCode(s.getBlockCode());
        entity.setBlockName(s.getBlock());
        entity.setIconSvgUri(s.getIconSvgUri());
        return entity;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
