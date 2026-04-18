package com.evaristof.mtgcollection.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable in-memory record describing an asynchronous spreadsheet-import job.
 *
 * <p>Two background workers touch it:</p>
 * <ul>
 *   <li>The import worker updates {@link #total}, {@link #processed},
 *       {@link #errors}, {@link #status}, and {@link #resultBytes}.</li>
 *   <li>The HTTP controller reads a snapshot via {@link #toSnapshot()}.</li>
 * </ul>
 */
public class ImportJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    private final UUID id = UUID.randomUUID();
    private final String fileName;
    private final Instant createdAt = Instant.now();

    private volatile Status status = Status.PENDING;
    private volatile int total = 0;
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger persisted = new AtomicInteger(0);
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private volatile String currentSheet;
    private volatile byte[] resultBytes;
    private volatile String resultFileName;
    private volatile String errorMessage;

    public ImportJob(String fileName) {
        this.fileName = fileName;
    }

    public UUID getId() { return id; }
    public String getFileName() { return fileName; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public int getTotal() { return total; }
    public int getProcessed() { return processed.get(); }
    public int getPersisted() { return persisted.get(); }
    public List<String> getErrors() { return errors; }
    public String getCurrentSheet() { return currentSheet; }
    public byte[] getResultBytes() { return resultBytes; }
    public String getResultFileName() { return resultFileName; }
    public String getErrorMessage() { return errorMessage; }

    public void setStatus(Status status) { this.status = status; }
    public void setTotal(int total) { this.total = total; }
    public int incrementProcessed() { return processed.incrementAndGet(); }
    public int incrementPersisted() { return persisted.incrementAndGet(); }
    public void addError(String message) { errors.add(message); }
    public void setCurrentSheet(String currentSheet) { this.currentSheet = currentSheet; }
    public void setResult(byte[] bytes, String name) {
        this.resultBytes = bytes;
        this.resultFileName = name;
    }
    public void setErrorMessage(String message) { this.errorMessage = message; }

    public Snapshot toSnapshot() {
        return new Snapshot(
                id.toString(),
                fileName,
                status.name(),
                total,
                processed.get(),
                persisted.get(),
                List.copyOf(errors),
                currentSheet,
                resultFileName,
                errorMessage);
    }

    /** Serializable view returned by the status endpoint. */
    public record Snapshot(
            String id,
            String fileName,
            String status,
            int total,
            int processed,
            int persisted,
            List<String> errors,
            String currentSheet,
            String resultFileName,
            String errorMessage
    ) {}
}
