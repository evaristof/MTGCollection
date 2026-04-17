package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import com.evaristof.mtgcollection.service.SetPersistenceService;
import com.evaristof.mtgcollection.service.SetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sets")
public class SetController {

    private final SetService setService;
    private final SetPersistenceService setPersistenceService;

    public SetController(SetService setService, SetPersistenceService setPersistenceService) {
        this.setService = setService;
        this.setPersistenceService = setPersistenceService;
    }

    /**
     * GET /api/sets — returns all Magic sets fetched live from Scryfall.
     */
    @GetMapping
    public List<ScryfallSet> listSets() {
        return setService.listAllSets();
    }

    /**
     * POST /api/sets/sync — fetches the set list from Scryfall and
     * persists it into the in-memory database, returning the stored entities.
     */
    @PostMapping("/sync")
    public List<MagicSet> syncSets() {
        return setPersistenceService.syncSetsFromScryfall();
    }
}
