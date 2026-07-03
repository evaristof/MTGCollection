package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.DataJob;
import com.evaristof.mtgcollection.service.DataJobService;
import com.evaristof.mtgcollection.service.DataManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/rebuild-scanner-model")
    public ResponseEntity<Map<String, String>> rebuildScannerModel() {
        DataJob job = dataJobService.submit("rebuild-scanner-model",
                dataManagementService::rebuildScannerModel);
        return ResponseEntity.accepted().body(Map.of(
                "job_id", job.getId().toString(),
                "message", "Reconstrução do modelo do scanner iniciada."));
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
