package com.evaristof.mtgcollection.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable in-memory record describing an asynchronous data-management job
 * (bulk image download, prune, etc.). Mirrors {@link ImportJob}: a worker
 * thread updates the counters/status; the HTTP controller reads a snapshot
 * via {@link #toSnapshot()} for progress polling.
 */
public class DataJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    private final UUID id = UUID.randomUUID();
    private final String type;
    private final Instant createdAt = Instant.now();

    private volatile Status status = Status.PENDING;
    // Cooperative cancellation: the worker checks this between units of work
    // and stops gracefully (used by the "finalizar download" button).
    private volatile boolean cancelRequested = false;
    private volatile int total = 0;
    private final AtomicInteger processed = new AtomicInteger(0);
    // "succeeded" = images downloaded / rows created / objects deleted,
    // depending on the job type.
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private volatile String message;

    public DataJob(String type) {
        this.type = type;
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void requestCancel() { this.cancelRequested = true; }
    public int getTotal() { return total; }
    public int getProcessed() { return processed.get(); }
    public int getSucceeded() { return succeeded.get(); }
    public int getSkipped() { return skipped.get(); }
    public List<String> getErrors() { return errors; }
    public String getMessage() { return message; }

    public void setStatus(Status status) { this.status = status; }
    public void setTotal(int total) { this.total = total; }
    public void setProcessed(int value) { processed.set(value); }
    public int incrementProcessed() { return processed.incrementAndGet(); }
    public int incrementSucceeded() { return succeeded.incrementAndGet(); }
    public int incrementSkipped() { return skipped.incrementAndGet(); }
    public void addError(String message) {
        // Cap to avoid an unbounded error list on a large failing job.
        if (errors.size() < 200) {
            errors.add(message);
        }
    }
    public void setMessage(String message) { this.message = message; }

    public Snapshot toSnapshot() {
        return new Snapshot(
                id.toString(),
                type,
                status.name(),
                total,
                processed.get(),
                succeeded.get(),
                skipped.get(),
                List.copyOf(errors),
                message);
    }

    /** Serializable view returned by the status endpoint. */
    public record Snapshot(
            String id,
            String type,
            String status,
            int total,
            int processed,
            int succeeded,
            int skipped,
            List<String> errors,
            String message
    ) {}
}
