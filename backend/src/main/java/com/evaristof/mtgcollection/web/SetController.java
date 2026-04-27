package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallSet;
import com.evaristof.mtgcollection.service.SetImageService;
import com.evaristof.mtgcollection.service.SetPersistenceService;
import com.evaristof.mtgcollection.service.SetService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/sets")
public class SetController {

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
     * persists it into the in-memory database, then fires off an async
     * icon download to MinIO so the HTTP response returns immediately.
     */
    @PostMapping("/sync")
    public List<MagicSet> syncSets() {
        List<MagicSet> saved = setPersistenceService.syncSetsFromScryfall();
        setImageService.syncAllSetIconsAsync();
        return saved;
    }

    /**
     * GET /api/sets/{code}/icon — returns the SVG icon for a set.
     */
    @GetMapping("/{code}/icon")
    public ResponseEntity<?> getSetIcon(@PathVariable("code") String code) {
        try {
            byte[] svg = setImageService.getSetIcon(code);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "image/svg+xml");
            headers.setCacheControl("public, max-age=86400");
            return new ResponseEntity<>(svg, headers, HttpStatus.OK);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve set icon: " + e.getMessage()));
        }
    }

    /**
     * POST /api/sets/sync-icons — downloads all uncached set icons from
     * Scryfall and stores them in MinIO (synchronous).
     */
    @PostMapping("/sync-icons")
    public ResponseEntity<?> syncSetIcons() {
        try {
            int count = setImageService.syncAllSetIcons();
            return ResponseEntity.ok(Map.of("synced", count));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to sync set icons: " + e.getMessage()));
        }
    }
}
