package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.SetImageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sets")
public class SetImageController {

    private final SetImageService setImageService;

    public SetImageController(SetImageService setImageService) {
        this.setImageService = setImageService;
    }

    @GetMapping("/{code}/icon")
    public ResponseEntity<?> getSetIcon(@PathVariable("code") String code) {
        try {
            byte[] svg = setImageService.getSetIcon(code);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "image/svg+xml");
            headers.setCacheControl("public, max-age=86400");
            return new ResponseEntity<>(svg, headers, HttpStatus.OK);
        } catch (java.util.NoSuchElementException e) {
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
