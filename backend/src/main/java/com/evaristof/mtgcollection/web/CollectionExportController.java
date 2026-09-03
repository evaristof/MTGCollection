package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CollectionExportService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Downloads the whole collection as a spreadsheet in the importer's layout —
 * the counterpart of {@code POST /api/collection/import}.
 */
@RestController
@RequestMapping("/api/collection/export")
public class CollectionExportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CollectionExportService service;

    public CollectionExportController(CollectionExportService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ByteArrayResource> export() {
        byte[] data = service.exportCollection();
        String fileName = "colecao-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(XLSX)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }
}
