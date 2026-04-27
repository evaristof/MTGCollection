package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.service.CardImageMatchService;
import com.evaristof.mtgcollection.service.MinioStorageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/scanner")
public class ScannerController {

    private final CardImageMatchService matchService;
    private final MinioStorageService minioStorage;

    public ScannerController(CardImageMatchService matchService,
                             MinioStorageService minioStorage) {
        this.matchService = matchService;
        this.minioStorage = minioStorage;
    }

    @PostMapping("/match")
    public ResponseEntity<Map<String, Object>> matchImage(@RequestParam("image") MultipartFile image) {
        try {
            BufferedImage buffered = ImageIO.read(image.getInputStream());
            if (buffered == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "matched", false,
                        "error", "Could not decode image"));
            }

            CardImageMatchService.MatchResult matchResult = matchService.findBestMatch(buffered);

            Map<String, Object> result = new LinkedHashMap<>();
            if (matchResult != null) {
                result.put("matched", true);
                CardImageHash match = matchResult.card();
                result.put("card_name", match.getCardName());
                result.put("set_code", match.getSetCode());
                result.put("collector_number", match.getCollectorNumber());
                result.put("confidence", matchResult.confidence());
                result.put("image_url", "/api/scanner/image/" + match.getSetCode() + "/" + match.getCollectorNumber());
            } else {
                result.put("matched", false);
                result.put("card_name", null);
                result.put("set_code", null);
                result.put("collector_number", null);
                result.put("confidence", 0.0);
                result.put("image_url", null);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return ResponseEntity.internalServerError().body(Map.of(
                    "matched", false,
                    "error", msg));
        }
    }

    @PostMapping("/sync-images")
    public ResponseEntity<Map<String, String>> syncImages(@RequestParam("set") String setCode) {
        try {
            matchService.syncImagesFromScryfallAsync(setCode);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted",
                    "message", "Sync started for set: " + setCode));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", msg));
        }
    }

    @GetMapping("/image/{setCode}/{collectorNumber}")
    public ResponseEntity<ByteArrayResource> getImage(@PathVariable String setCode,
                                                      @PathVariable String collectorNumber) {
        try {
            var hashOpt = matchService.findHashBySetAndNumber(setCode, collectorNumber);
            if (hashOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = minioStorage.download(hashOpt.get().getMinioPath());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(new ByteArrayResource(data));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
