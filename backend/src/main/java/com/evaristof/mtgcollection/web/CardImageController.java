package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CardImageService;
import com.evaristof.mtgcollection.service.ScryfallLookupException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Serves card images on demand.
 *
 * <p>{@code GET /api/collection/cards/{id}/image?face=0} returns the PNG bytes
 * for the front face. Use {@code face=1} for the back face of double-faced
 * cards. The {@code /image/info} endpoint returns metadata including the
 * number of faces.</p>
 */
@RestController
@RequestMapping("/api/collection/cards")
public class CardImageController {

    private final CardImageService cardImageService;

    public CardImageController(CardImageService cardImageService) {
        this.cardImageService = cardImageService;
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<?> getImage(@PathVariable("id") Long id,
                                      @RequestParam(value = "face", defaultValue = "0") int face) {
        try {
            byte[] image = cardImageService.getCardImage(id, face);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setCacheControl("public, max-age=86400");
            return new ResponseEntity<>(image, headers, HttpStatus.OK);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("message", e.getMessage()));
        } catch (ScryfallLookupException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "Scryfall: " + e.getMessage(), "url", e.getUrl()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve image: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/image/info")
    public ResponseEntity<?> getImageInfo(@PathVariable("id") Long id) {
        try {
            int faceCount = cardImageService.getFaceCount(id);
            return ResponseEntity.ok(Map.of("face_count", faceCount));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to get image info: " + e.getMessage()));
        }
    }
}
