package com.sarthiflow.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sarthiflow.model.TATTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class NDJSONSpoolWriter {
    private static final Logger logger = LoggerFactory.getLogger(NDJSONSpoolWriter.class);
    private static final String SPOOL_FILENAME = "tat-events.ndjson";

    private final Path spoolPath;
    private final Path spoolFile;
    private final Gson gson;

    public NDJSONSpoolWriter(String spoolDirectory) {
        this.spoolPath = Paths.get(spoolDirectory);
        this.spoolFile = this.spoolPath.resolve(SPOOL_FILENAME);
        this.gson = new GsonBuilder().create();

        initializeSpoolDirectory();
    }

    private void initializeSpoolDirectory() {
        try {
            if (!Files.exists(spoolPath)) {
                Files.createDirectories(spoolPath);
                logger.info("Created spool directory: {}", spoolPath.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to create spool directory: {}", spoolPath, e);
            throw new RuntimeException("Spool directory initialization failed", e);
        }
    }

    /**
     * Write a completed transaction to NDJSON spool (durable)
     *
     * Format: One JSON object per line (NDJSON)
     * Each line contains: tat_ms, stan, channel, response_code, timestamp, status
     *
     * Example:
     * {"tat_ms":186,"stan":"123456","channel":"UPI","response_code":"000","timestamp":1725088946721,"status":"COMPLETED"}
     */
    public synchronized void writeTransaction(TATTransaction transaction) {
        try {
            Map<String, Object> event = buildEventMap(transaction);
            String ndjsonLine = gson.toJson(event);

            // Append to spool file (durable write)
            Files.write(
                spoolFile,
                (ndjsonLine + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );

            logger.debug("Wrote transaction to spool: {}", transaction.getCorrelationKey());

        } catch (IOException e) {
            logger.error("Failed to write transaction to spool: {}", transaction.getCorrelationKey(), e);
            // Don't throw - allow application to continue even if spool write fails
        }
    }

    private Map<String, Object> buildEventMap(TATTransaction transaction) {
        Map<String, Object> event = new LinkedHashMap<>();

        // Core metrics
        event.put("tat_ms", transaction.getTatMillis());
        event.put("stan", transaction.getStan());
        event.put("channel", transaction.getChannel());
        event.put("response_code", transaction.getResponseCode());

        // Metadata
        event.put("timestamp", System.currentTimeMillis());
        event.put("status", transaction.getTatMillis() >= 0 ? "COMPLETED" : "TIMEOUT");

        return event;
    }

    /**
     * Read all pending events from spool (for Bindplane or other readers)
     * Returns list of NDJSON lines (raw strings)
     */
    public List<String> readPendingEvents() {
        List<String> events = new ArrayList<>();
        try {
            if (Files.exists(spoolFile)) {
                events = Files.readAllLines(spoolFile, StandardCharsets.UTF_8);
                logger.info("Read {} events from spool", events.size());
            }
        } catch (IOException e) {
            logger.error("Failed to read spool file: {}", spoolFile, e);
        }
        return events;
    }

    /**
     * Clear spool file after successful export (acknowledgement)
     * Use only after confirming downstream delivery (Bindplane or Dynatrace)
     */
    public synchronized void clearSpool() {
        try {
            if (Files.exists(spoolFile)) {
                Files.delete(spoolFile);
                logger.info("Cleared spool file: {}", spoolFile.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to clear spool file: {}", spoolFile, e);
        }
    }

    /**
     * Archive spool file with timestamp for audit trail
     */
    public synchronized void archiveSpool() {
        try {
            if (Files.exists(spoolFile)) {
                String timestamp = String.valueOf(System.currentTimeMillis());
                Path archiveFile = spoolPath.resolve("tat-events-" + timestamp + ".ndjson.gz");

                // For now, just rename to archive
                Files.move(spoolFile, archiveFile);
                logger.info("Archived spool to: {}", archiveFile.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to archive spool file", e);
        }
    }

    /**
     * Get spool file size (for monitoring)
     */
    public long getSpoolFileSize() {
        try {
            if (Files.exists(spoolFile)) {
                return Files.size(spoolFile);
            }
        } catch (IOException e) {
            logger.warn("Failed to get spool file size", e);
        }
        return 0;
    }

    /**
     * Get spool directory path
     */
    public Path getSpoolDirectory() {
        return spoolPath;
    }

    public void close() {
        // No resources to close (file operations are not persistent)
        logger.info("NDJSONSpoolWriter closed");
    }
}

