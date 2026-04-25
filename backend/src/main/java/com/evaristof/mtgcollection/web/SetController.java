package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import com.evaristof.mtgcollection.service.SetImageService;
import com.evaristof.mtgcollection.service.SetPersistenceService;
import com.evaristof.mtgcollection.service.SetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sets")
public class SetController {

    private static final Logger log = LoggerFactory.getLogger(SetController.class);

    private final SetService setService;
    private final SetPersistenceService setPersistenceService;
    private final SetImageService setImageService;

    public SetController(SetService setService, SetPersistenceService setPersistenceService,
                         SetImageService setImageService) {
        this.setService = setService;
        this.setPersistenceService = setPersistenceService;
        this.setImageService = setImageService;
    }

    /**
     * GET /api/sets — returns all Magic sets fetched live from Scryfall.
     */
    @GetMapping
    public List<ScryfallSet> listSets() {
        return setService.listAllSets();
    }

    /**
     * POST /api/sets/sync — fetches the set list from Scryfall,
     * persists it into the in-memory database, then downloads set icons
     * to MinIO (outside the DB transaction).
     */
    @PostMapping("/sync")
    public List<MagicSet> syncSets() {
        List<MagicSet> saved = setPersistenceService.syncSetsFromScryfall();
        try {
            setImageService.syncAllSetIcons();
        } catch (RuntimeException e) {
            log.warn("Set icon sync failed (best-effort): {}", e.getMessage());
        }
        return saved;
    }
}
