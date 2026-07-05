package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.CardImageHash;
import com.evaristof.mtgcollection.service.CardImageMatchService;
import com.evaristof.mtgcollection.service.CardSplitterService;
import com.evaristof.mtgcollection.service.ImageOrientationUtil;
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
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scanner")
public class ScannerController {

    private final CardImageMatchService matchService;
    private final MinioStorageService minioStorage;
    private final CardSplitterService cardSplitter;

    public ScannerController(CardImageMatchService matchService,
                             MinioStorageService minioStorage,
                             CardSplitterService cardSplitter) {
        this.matchService = matchService;
        this.minioStorage = minioStorage;
        this.cardSplitter = cardSplitter;
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

    /**
     * Bulk scan, phase 1: a photo with MANY cards (e.g. a binder page) is split
     * into one crop per card ({@link CardSplitterService}). Returns only the
     * crops (as base64 data URLs) — NOT the matches. The client shows a row per
     * crop immediately, then matches each crop through the normal {@code /match}
     * endpoint one at a time, so the user sees the scan fill in progressively.
     */
    @PostMapping("/split")
    public ResponseEntity<Map<String, Object>> split(@RequestParam("image") MultipartFile image) {
        try {
            // Honour EXIF orientation so the split sees upright cards.
            BufferedImage buffered = ImageOrientationUtil.readUpright(image.getBytes());
            if (buffered == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Could not decode image"));
            }

            List<BufferedImage> crops = cardSplitter.splitCards(buffered);
            List<Map<String, Object>> out = new ArrayList<>(crops.size());
            for (int i = 0; i < crops.size(); i++) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", i);
                entry.put("crop_image", toDataUrl(crops.get(i)));
                out.add(entry);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("count", out.size());
            body.put("crops", out);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return ResponseEntity.internalServerError().body(Map.of("error", msg));
        }
    }

    private static String toDataUrl(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // Diagnostic endpoint — given a photo and the known-correct set/number,
    // reports where the expected card lands in the ORB art-match ranking
    // (rank, inliers, good matches) plus the OCR read. Handy for
    // investigating why a particular photo does or doesn't match.
    @PostMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(@RequestParam("image") MultipartFile image,
                                                        @RequestParam("set") String set,
                                                        @RequestParam("number") String number) {
        try {
            BufferedImage buffered = ImageIO.read(image.getInputStream());
            if (buffered == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not decode image"));
            }
            return ResponseEntity.ok(matchService.diagnose(buffered, set, number));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return ResponseEntity.internalServerError().body(Map.of("error", msg));
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

    @PostMapping("/populate-hashes")
    public ResponseEntity<Map<String, String>> populateHashes() {
        try {
            matchService.populateHashesFromMinioAsync();
            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted",
                    "message", "Hash population from MinIO started"));
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
