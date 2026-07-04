package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.service.DataJob;
import com.evaristof.mtgcollection.service.DataJobService;
import com.evaristof.mtgcollection.service.DataManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for the "Magic Data Management" screen: aggregate stats, bulk
 * download of the collection's images into MinIO, and pruning images that
 * aren't in the collection. The long-running operations return 202 with a
 * job id the frontend polls via {@code GET /jobs/{id}}.
 */
@RestController
@RequestMapping("/api/data-management")
public class DataManagementController {

    private final DataManagementService dataManagementService;
    private final DataJobService dataJobService;

    public DataManagementController(DataManagementService dataManagementService,
                                    DataJobService dataJobService) {
        this.dataManagementService = dataManagementService;
        this.dataJobService = dataJobService;
    }

    @GetMapping("/stats")
    public ResponseEntity<DataManagementService.DataStats> stats() {
        return ResponseEntity.ok(dataManagementService.stats());
    }

    @PostMapping("/download-collection")
    public ResponseEntity<Map<String, String>> downloadCollection() {
        DataJob job = dataJobService.submit("download-collection",
                dataManagementService::downloadCollectionImages);
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Download das imagens da coleção iniciado."));
    }

    @DeleteMapping("/prune-outside-collection")
    public ResponseEntity<Map<String, String>> pruneOutsideCollection() {
        DataJob job = dataJobService.submit("prune-outside-collection",
                dataManagementService::pruneOutsideCollection);
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Remoção das imagens fora da coleção iniciada."));
    }

    @PostMapping("/download-all-scryfall")
    public ResponseEntity<Map<String, String>> downloadAllScryfall() {
        DataJob job = dataJobService.submit("download-all-scryfall",
                dataManagementService::downloadAllScryfall);
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Download de todas as edições do Scryfall iniciado."));
    }

    @PostMapping("/download-set")
    public ResponseEntity<Map<String, String>> downloadSet(@RequestParam("set") String set) {
        DataJob job = dataJobService.submit("download-set",
                j -> dataManagementService.downloadSetImages(j, set));
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Importação do set " + set + " iniciada."));
    }

    @DeleteMapping("/delete-set")
    public ResponseEntity<Map<String, String>> deleteSet(@RequestParam("set") String set) {
        DataJob job = dataJobService.submit("delete-set",
                j -> dataManagementService.deleteSet(j, set));
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Remoção do set " + set + " iniciada."));
    }

    @GetMapping("/blacklist")
    public ResponseEntity<List<MagicSet>> listBlacklist() {
        return ResponseEntity.ok(dataManagementService.listBlacklist());
    }

    @PostMapping("/blacklist")
    public ResponseEntity<Map<String, String>> addToBlacklist(@RequestParam("set") String set) {
        dataManagementService.setBlacklisted(set, true);
        return ResponseEntity.ok(Map.of("message", "Set " + set + " adicionado à blacklist."));
    }

    @DeleteMapping("/blacklist")
    public ResponseEntity<Map<String, String>> removeFromBlacklist(@RequestParam("set") String set) {
        dataManagementService.setBlacklisted(set, false);
        return ResponseEntity.ok(Map.of("message", "Set " + set + " removido da blacklist."));
    }

    @PostMapping("/blacklist/purge")
    public ResponseEntity<Map<String, String>> purgeBlacklist() {
        DataJob job = dataJobService.submit("purge-blacklist",
                dataManagementService::purgeBlacklisted);
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Remoção das imagens dos sets da blacklist iniciada."));
    }

    @PostMapping("/rebuild-scanner-model")
    public ResponseEntity<Map<String, String>> rebuildScannerModel() {
        DataJob job = dataJobService.submit("rebuild-scanner-model",
                dataManagementService::rebuildScannerModel);
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Reconstrução do modelo do scanner iniciada."));
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<DataJob.Snapshot> cancelJob(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return dataJobService.findJob(uuid)
                .map(job -> {
                    job.requestCancel();
                    return ResponseEntity.accepted().body(job.toSnapshot());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/active")
    public ResponseEntity<DataJob.Snapshot> activeJob() {
        return dataJobService.activeJob()
                .map(job -> ResponseEntity.ok(job.toSnapshot()))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<DataJob.Snapshot> jobStatus(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return dataJobService.findJob(uuid)
                .map(job -> ResponseEntity.ok(job.toSnapshot()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
