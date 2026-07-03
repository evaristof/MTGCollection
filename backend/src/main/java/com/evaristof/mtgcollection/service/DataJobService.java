package com.evaristof.mtgcollection.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs data-management operations (bulk download, prune) asynchronously and
 * tracks their progress, mirroring {@link ImportJobService}. Jobs run one at a
 * time on a single worker thread so we never hammer Scryfall/MinIO with
 * overlapping bulk operations. Callers get a {@link DataJob} back immediately
 * and poll {@link #findJob(UUID)} for progress.
 */
@Service
public class DataJobService {

    private static final Logger log = LoggerFactory.getLogger(DataJobService.class);

    private final Map<UUID, DataJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "data-management-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Submits a job identified by {@code type}, running {@code work} on the
     * worker thread. The work consumer receives the job to update its
     * progress counters. Returns the job immediately.
     */
    public DataJob submit(String type, Consumer<DataJob> work) {
        DataJob job = new DataJob(type);
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job, work));
        return job;
    }

    public Optional<DataJob> findJob(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    private void run(DataJob job, Consumer<DataJob> work) {
        try {
            job.setStatus(DataJob.Status.RUNNING);
            work.accept(job);
            if (job.getStatus() == DataJob.Status.RUNNING) {
                job.setStatus(DataJob.Status.DONE);
            }
        } catch (Exception e) {
            log.error("Data job {} ({}) failed", job.getId(), job.getType(), e);
            job.setMessage("Falha: " + e.getMessage());
            job.addError(e.getMessage());
            job.setStatus(DataJob.Status.FAILED);
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
