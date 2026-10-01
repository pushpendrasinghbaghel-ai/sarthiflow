package com.sarthiflow.pipeline.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sarthiflow.pipeline.correlation.CorrelatedEvent;
import com.sarthiflow.pipeline.event.RawEvent;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class SqlitePipelineStore implements AutoCloseable {
    private static final Type EVENT_FIELDS_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private final Connection connection;
    private final Gson gson = new Gson();

    public SqlitePipelineStore(String databasePath) throws SQLException {
        if (databasePath == null || databasePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Database path is required");
        }
        try {
            Path path = Paths.get(databasePath).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to create database directory", e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        initializeSchema();
    }

    private void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE IF NOT EXISTS pipeline_raw_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "blueprint_name TEXT NOT NULL,"
                    + "event_hash TEXT NOT NULL,"
                    + "correlation_key TEXT NOT NULL,"
                    + "event_type TEXT NOT NULL,"
                    + "event_timestamp TEXT NOT NULL,"
                    + "payload_json TEXT NOT NULL,"
                    + "correlated INTEGER NOT NULL DEFAULT 0,"
                    + "UNIQUE(blueprint_name, event_hash))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_pipeline_raw_pending "
                    + "ON pipeline_raw_events(blueprint_name, correlated, event_timestamp)");
            statement.execute("CREATE TABLE IF NOT EXISTS pipeline_correlated_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "blueprint_name TEXT NOT NULL,"
                    + "correlation_key TEXT NOT NULL,"
                    + "request_timestamp TEXT NOT NULL,"
                    + "response_timestamp TEXT NOT NULL,"
                    + "latency_ms INTEGER NOT NULL,"
                    + "outcome TEXT NOT NULL,"
                    + "dimensions_json TEXT NOT NULL,"
                    + "request_json TEXT NOT NULL,"
                    + "response_json TEXT NOT NULL,"
                    + "exported_at TEXT,"
                    + "UNIQUE(blueprint_name, correlation_key, request_timestamp, response_timestamp))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_pipeline_correlated_export "
                    + "ON pipeline_correlated_events(blueprint_name, exported_at, id)");
            statement.execute("CREATE TABLE IF NOT EXISTS pipeline_checkpoints ("
                    + "source_id TEXT PRIMARY KEY,"
                    + "offset_bytes INTEGER NOT NULL,"
                    + "file_identity TEXT,"
                    + "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    public synchronized long saveRawEvent(String blueprintName, String correlationKey, String eventType,
                                          Instant timestamp, RawEvent event) throws SQLException {
        requireText(blueprintName, "Blueprint name");
        requireText(correlationKey, "Correlation key");
        requireText(eventType, "Event type");
        if (timestamp == null || event == null) {
            throw new IllegalArgumentException("Event timestamp and payload are required");
        }
        String payload = gson.toJson(event.getFields());
        String eventHash = hash(blueprintName + "\n" + correlationKey + "\n" + eventType + "\n"
                + timestamp + "\n" + gson.toJson(new TreeMap<>(event.getFields())));
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO pipeline_raw_events "
                        + "(blueprint_name, event_hash, correlation_key, event_type, event_timestamp, payload_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, blueprintName);
            insert.setString(2, eventHash);
            insert.setString(3, correlationKey);
            insert.setString(4, eventType);
            insert.setString(5, timestamp.toString());
            insert.setString(6, payload);
            insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM pipeline_raw_events WHERE blueprint_name = ? AND event_hash = ?")) {
            select.setString(1, blueprintName);
            select.setString(2, eventHash);
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    return result.getLong("id");
                }
            }
        }
        throw new SQLException("Raw event insert did not produce an identifier");
    }

    public synchronized List<StoredRawEvent> loadUncorrelatedEvents(String blueprintName) throws SQLException {
        List<StoredRawEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, correlation_key, event_type, event_timestamp, payload_json "
                        + "FROM pipeline_raw_events WHERE blueprint_name = ? AND correlated = 0 "
                        + "ORDER BY event_timestamp, id")) {
            statement.setString(1, blueprintName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new StoredRawEvent(result.getLong("id"),
                            result.getString("correlation_key"), result.getString("event_type"),
                            Instant.parse(result.getString("event_timestamp")),
                            new RawEvent(gson.fromJson(result.getString("payload_json"), EVENT_FIELDS_TYPE))));
                }
            }
        }
        return events;
    }

    public synchronized long saveCorrelatedEvent(String blueprintName, String correlationKey,
                                                 long requestEventId, long responseEventId,
                                                 CorrelatedEvent event) throws SQLException {
        requireText(blueprintName, "Blueprint name");
        requireText(correlationKey, "Correlation key");
        if (requestEventId <= 0 || responseEventId <= 0 || event == null) {
            throw new IllegalArgumentException("Stored raw-event identifiers and correlated event are required");
        }
        String requestTimestamp = event.getRequestTimestamp() == null
            ? null : event.getRequestTimestamp().toString();
        String responseTimestamp = event.getResponseTimestamp() == null
            ? null : event.getResponseTimestamp().toString();
        if (requestTimestamp == null || responseTimestamp == null) {
            throw new IllegalArgumentException("Correlated events must include the normalized timestamp field");
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO pipeline_correlated_events "
                            + "(blueprint_name, correlation_key, request_timestamp, response_timestamp, latency_ms, "
                            + "outcome, dimensions_json, request_json, response_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, blueprintName);
                insert.setString(2, correlationKey);
                insert.setString(3, requestTimestamp);
                insert.setString(4, responseTimestamp);
                insert.setLong(5, event.getLatencyMs());
                insert.setString(6, event.getOutcome());
                insert.setString(7, gson.toJson(event.getDimensions()));
                insert.setString(8, gson.toJson(event.getRequest().getFields()));
                insert.setString(9, gson.toJson(event.getResponse().getFields()));
                insert.executeUpdate();
            }
            markRawEventCorrelated(requestEventId, blueprintName, correlationKey);
            markRawEventCorrelated(responseEventId, blueprintName, correlationKey);
            long id = findCorrelatedEventId(blueprintName, correlationKey, requestTimestamp, responseTimestamp);
            connection.commit();
            return id;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    public synchronized List<StoredCorrelatedEvent> findUnexportedEvents(String blueprintName, int limit)
            throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        List<StoredCorrelatedEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, request_timestamp, response_timestamp, latency_ms, outcome, "
                    + "dimensions_json, request_json, response_json "
                        + "FROM pipeline_correlated_events WHERE blueprint_name = ? AND exported_at IS NULL "
                        + "ORDER BY id LIMIT ?")) {
            statement.setString(1, blueprintName);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, String> request = gson.fromJson(result.getString("request_json"), EVENT_FIELDS_TYPE);
                    Map<String, String> response = gson.fromJson(result.getString("response_json"), EVENT_FIELDS_TYPE);
                    Map<String, String> dimensions = gson.fromJson(result.getString("dimensions_json"), EVENT_FIELDS_TYPE);
                    CorrelatedEvent event = new CorrelatedEvent(new RawEvent(request), new RawEvent(response),
                            result.getLong("latency_ms"), result.getString("outcome"), dimensions,
                            Instant.parse(result.getString("request_timestamp")),
                            Instant.parse(result.getString("response_timestamp")));
                    events.add(new StoredCorrelatedEvent(result.getLong("id"), event));
                }
            }
        }
        return events;
    }

    public synchronized void markExported(long correlatedEventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pipeline_correlated_events SET exported_at = ? WHERE id = ? AND exported_at IS NULL")) {
            statement.setString(1, Instant.now().toString());
            statement.setLong(2, correlatedEventId);
            statement.executeUpdate();
        }
    }

    public synchronized void saveCheckpoint(String sourceId, long offsetBytes, String fileIdentity)
            throws SQLException {
        requireText(sourceId, "Source identifier");
        if (offsetBytes < 0) {
            throw new IllegalArgumentException("Checkpoint offset cannot be negative");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pipeline_checkpoints (source_id, offset_bytes, file_identity, updated_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT(source_id) DO UPDATE SET "
                        + "offset_bytes = excluded.offset_bytes, file_identity = excluded.file_identity, "
                        + "updated_at = excluded.updated_at")) {
            statement.setString(1, sourceId);
            statement.setLong(2, offsetBytes);
            statement.setString(3, fileIdentity);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public synchronized Optional<ReaderCheckpoint> getCheckpoint(String sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_id, offset_bytes, file_identity FROM pipeline_checkpoints WHERE source_id = ?")) {
            statement.setString(1, sourceId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(new ReaderCheckpoint(result.getString("source_id"),
                            result.getLong("offset_bytes"), result.getString("file_identity")));
                }
            }
        }
        return Optional.empty();
    }

    private void markRawEventCorrelated(long eventId, String blueprintName, String correlationKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pipeline_raw_events SET correlated = 1 "
                        + "WHERE id = ? AND blueprint_name = ? AND correlation_key = ?")) {
            statement.setLong(1, eventId);
            statement.setString(2, blueprintName);
            statement.setString(3, correlationKey);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Raw event not found: " + eventId);
            }
        }
    }

    private long findCorrelatedEventId(String blueprintName, String correlationKey,
                                       String requestTimestamp, String responseTimestamp) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM pipeline_correlated_events WHERE blueprint_name = ? AND correlation_key = ? "
                        + "AND request_timestamp = ? AND response_timestamp = ?")) {
            statement.setString(1, blueprintName);
            statement.setString(2, correlationKey);
            statement.setString(3, requestTimestamp);
            statement.setString(4, responseTimestamp);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong("id");
                }
            }
        }
        throw new SQLException("Correlated-event insert did not produce an identifier");
    }

    private String hash(String value) throws SQLException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                encoded.append(String.format("%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (Exception e) {
            throw new SQLException("Unable to hash raw event", e);
        }
    }

    private void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (!connection.isClosed()) {
            connection.close();
        }
    }
}