package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CollectionReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Backs the "Reconciliação Coleção" screen: takes the same spreadsheet the
 * importer accepts and answers with the differences against the registered
 * collection. Read-only — the screen applies each difference through the
 * normal cards API.
 */
@RestController
@RequestMapping("/api/collection/reconcile")
public class CollectionReconciliationController {

    private final CollectionReconciliationService service;

    public CollectionReconciliationController(CollectionReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> reconcile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Envie a planilha no campo 'file'."));
        }
        try {
            return ResponseEntity.ok(service.reconcile(file.getBytes()));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Falha lendo o arquivo: " + e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Falha na reconciliação: " + msg));
        }
    }
}
