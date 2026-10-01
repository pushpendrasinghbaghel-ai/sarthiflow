package com.sarthiflow.pipeline.reader;

import com.sarthiflow.pipeline.config.PipelineConfiguration;
import com.sarthiflow.pipeline.correlation.CorrelationEngine;
import com.sarthiflow.pipeline.parse.EventParseException;
import com.sarthiflow.pipeline.parse.EventParser;
import com.sarthiflow.pipeline.store.SqlitePipelineStore;
import com.sarthiflow.pipeline.store.StoredCorrelatedPair;
import com.sarthiflow.pipeline.store.StoredRawEvent;
import com.sarthiflow.pipeline.event.RawEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class ReaderService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ReaderService.class);
    private static final Pattern ISO8583_SEPARATOR = Pattern.compile("^\\s*<={5,}>\\s*$");

    private final PipelineConfiguration config;
    private final SqlitePipelineStore store;
    private final EventParser parser;
    private final CorrelationEngine correlationEngine;
    private final ExecutorService workers;
    private volatile boolean running;

    public ReaderService(PipelineConfiguration config) throws SQLException {
        if (config.getInputPaths().isEmpty()) {
            throw new IllegalArgumentException("Reader requires at least one configured input path");
        }
        this.config = config;
        this.store = new SqlitePipelineStore(config.getDatabasePath());
        this.parser = config.createParser();
        this.correlationEngine = new CorrelationEngine(config.getBlueprint());
        this.workers = Executors.newFixedThreadPool(config.getWorkerCount());
    }

    public void processOnce() throws IOException, SQLException {
        List<Path> sources = config.getInputPaths();
        AtomicInteger next = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        int taskCount = Math.min(config.getWorkerCount(), sources.size());
        for (int worker = 0; worker < taskCount; worker++) {
            tasks.add(workers.submit(() -> {
                int index;
                while ((index = next.getAndIncrement()) < sources.size()) {
                    try {
                        processFile(sources.get(index));
                    } catch (IOException | SQLException e) {
                        throw new ReaderTaskException(e);
                    }
                }
            }));
        }
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Reader was interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() instanceof ReaderTaskException
                        ? e.getCause().getCause() : e.getCause();
                if (cause instanceof SQLException) {
                    throw (SQLException) cause;
                }
                throw new IOException("Unable to read configured source", cause);
            }
        }
        correlatePendingEvents();
    }

    public void runContinuously() throws IOException, SQLException, InterruptedException {
        running = true;
        while (running && !Thread.currentThread().isInterrupted()) {
            processOnce();
            Thread.sleep(config.getPollIntervalMillis());
        }
    }

    public void stop() {
        running = false;
    }

    private void processFile(Path path) throws IOException, SQLException {
        if (!Files.isRegularFile(path)) {
            logger.warn("Skipping non-file reader source: {}", path);
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        String sourceId = path.toAbsolutePath().normalize().toString();
        Optional<com.sarthiflow.pipeline.store.ReaderCheckpoint> checkpoint = store.getCheckpoint(sourceId);
        long offset = 0L;
        if (checkpoint.isPresent()) {
            long checkpointOffset = checkpoint.get().getOffsetBytes();
            if (checkpointOffset <= attributes.size()
                && fileIdentity(path, attributes, checkpointOffset).equals(checkpoint.get().getFileIdentity())) {
            offset = checkpointOffset;
            }
        }
        long lastCommittedOffset = offset;

        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            input.seek(offset);
            StringBuilder isoRecord = new StringBuilder();
            ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
            while (true) {
                int nextByte = input.read();
                if (nextByte < 0) {
                    break;
                }
                if (nextByte != '\n') {
                    lineBytes.write(nextByte);
                    continue;
                }
                String line = new String(lineBytes.toByteArray(), StandardCharsets.UTF_8);
                lineBytes.reset();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                long lineEndOffset = input.getFilePointer();
                if ("iso8583".equals(config.getFormat())) {
                    if (ISO8583_SEPARATOR.matcher(line).matches()) {
                        if (!isoRecord.toString().trim().isEmpty()) {
                            storeRecord(isoRecord.toString());
                        }
                        isoRecord.setLength(0);
                        store.saveCheckpoint(sourceId, lineEndOffset,
                            fileIdentity(path, attributes, lineEndOffset));
                        lastCommittedOffset = lineEndOffset;
                    } else {
                        isoRecord.append(line).append('\n');
                    }
                } else {
                    storeRecord(line);
                        store.saveCheckpoint(sourceId, lineEndOffset,
                            fileIdentity(path, attributes, lineEndOffset));
                    lastCommittedOffset = lineEndOffset;
                }
            }
            if ("iso8583".equals(config.getFormat()) && lastCommittedOffset == offset
                    && !isoRecord.toString().trim().isEmpty()) {
                logger.debug("Waiting for an ISO8583 record separator before checkpointing {}", path);
            }
        }
    }

    private String fileIdentity(Path path, BasicFileAttributes attributes, long offset) throws IOException {
        long start = Math.max(0L, offset - 128L);
        byte[] tail = new byte[(int) (offset - start)];
        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            input.seek(start);
            input.readFully(tail);
        }
        CRC32 checksum = new CRC32();
        checksum.update(tail);
        return String.valueOf(attributes.fileKey()) + "|" + attributes.creationTime().toMillis()
                + "|" + Long.toHexString(checksum.getValue());
    }

    private void storeRecord(String record) throws SQLException {
        if (record == null || record.trim().isEmpty()) {
            return;
        }
        try {
            Optional<RawEvent> parsed = parser.parse(record);
            if (!parsed.isPresent()) {
                return;
            }
            RawEvent event = parsed.get();
            String key = event.get(config.getBlueprint().getCorrelationKeyField());
            String type = event.get(config.getBlueprint().getRequestTypeField());
            if (!config.getBlueprint().getRequestValue().equals(type)) {
                type = event.get(config.getBlueprint().getResponseTypeField());
            }
            String timestamp = event.get(config.getBlueprint().getTimestampField());
            if (key == null || type == null || timestamp == null) {
                logger.warn("Parsed record is missing correlation key, event type, or timestamp; skipping it");
                return;
            }
            store.saveRawEvent(config.getBlueprintName(), key, type, Instant.parse(timestamp), event);
        } catch (EventParseException | RuntimeException e) {
            logger.warn("Skipping malformed input record: {}", e.getMessage());
        }
    }

    private void correlatePendingEvents() throws SQLException {
        List<StoredRawEvent> pending = store.loadUncorrelatedEvents(config.getBlueprintName());
        for (StoredCorrelatedPair pair : correlationEngine.correlateStored(pending)) {
            store.saveCorrelatedEvent(config.getBlueprintName(), pair.getRequest().getCorrelationKey(),
                    pair.getRequest().getId(), pair.getResponse().getId(), pair.getEvent());
        }
    }

    @Override
    public void close() throws SQLException {
        stop();
        workers.shutdownNow();
        store.close();
    }

    private static final class ReaderTaskException extends RuntimeException {
        private ReaderTaskException(Exception cause) {
            super(cause);
        }
    }
}