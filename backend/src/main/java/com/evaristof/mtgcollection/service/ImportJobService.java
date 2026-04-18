package com.evaristof.mtgcollection.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates asynchronous spreadsheet-import jobs.
 *
 * <p>Jobs are held in-memory keyed by {@link java.util.UUID}. A single worker
 * thread executes imports sequentially so we never flood Scryfall with
 * parallel requests. Each call to {@link #submit(String, byte[])} returns
 * immediately with a fresh {@link ImportJob}; callers then poll
 * {@link #findJob(UUID)} for progress and download the result via
 * {@link ImportJob#getResultBytes()}.</p>
 */
@Service
public class ImportJobService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

    private final Map<UUID, ImportJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "collection-import-worker");
        t.setDaemon(true);
        return t;
    });
    private final ObjectProvider<CollectionImportService> importServiceProvider;

    public ImportJobService(ObjectProvider<CollectionImportService> importServiceProvider) {
        this.importServiceProvider = importServiceProvider;
    }

    public ImportJob submit(String fileName, byte[] content) {
        ImportJob job = new ImportJob(fileName);
        jobs.put(job.getId(), job);
        executor.submit(() -> runJob(job, content));
        return job;
    }

    public Optional<ImportJob> findJob(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    private void runJob(ImportJob job, byte[] content) {
        try {
            job.setStatus(ImportJob.Status.RUNNING);
            CollectionImportService service = importServiceProvider.getObject();
            service.runImport(job, content);
            if (job.getStatus() == ImportJob.Status.RUNNING) {
                job.setStatus(ImportJob.Status.DONE);
            }
        } catch (Exception e) {
            log.error("Import job {} failed", job.getId(), e);
            job.setErrorMessage(e.getMessage());
            job.addError("Falha fatal: " + e.getMessage());
            job.setStatus(ImportJob.Status.FAILED);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
