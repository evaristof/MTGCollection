package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.ImportJob;
import com.evaristof.mtgcollection.service.ImportJobService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Endpoints for asynchronous Magic collection imports.
 *
 * <p>Typical flow:</p>
 * <ol>
 *   <li>{@code POST /api/collection/import} multipart with {@code file}.
 *       Returns {@code {"jobId": "..."}} and a {@code 202 Accepted} response.</li>
 *   <li>{@code GET /api/collection/import/{id}/status} — poll until
 *       {@code status == "DONE"} or {@code "FAILED"}.</li>
 *   <li>{@code GET /api/collection/import/{id}/download} — grabs the enriched
 *       {@code .xlsx} when the job is done.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/collection/import")
public class CollectionImportController {

    private final ImportJobService jobService;

    public CollectionImportController(ImportJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<ImportJob.Snapshot> submit(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ImportJob job = jobService.submit(file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.accepted().body(job.toSnapshot());
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ImportJob.Snapshot> status(@PathVariable("id") String id) {
        return jobService.findJob(parse(id))
                .map(job -> ResponseEntity.ok(job.toSnapshot()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable("id") String id) {
        ImportJob job = jobService.findJob(parse(id)).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (job.getStatus() != ImportJob.Status.DONE || job.getResultBytes() == null) {
            return ResponseEntity.status(409).build();
        }
        String filename = job.getResultFileName() != null ? job.getResultFileName() : "colecao.xlsx";
        String contentDisposition = "attachment; filename=\"" + filename + "\"; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(job.getResultBytes().length)
                .body(new ByteArrayResource(job.getResultBytes()));
    }

    private UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("jobId inválido: " + id);
        }
    }
}
