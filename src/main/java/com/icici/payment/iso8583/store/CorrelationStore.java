package com.icici.payment.iso8583.store;

import com.icici.payment.iso8583.metrics.NDJSONSpoolWriter;
import com.icici.payment.iso8583.model.ISO8583Message;
import com.icici.payment.iso8583.model.TATTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CorrelationStore {
    private static final Logger logger = LoggerFactory.getLogger(CorrelationStore.class);

    private final Map<String, TATTransaction> pendingTransactions;
    private final List<TATTransaction> completedTransactions;
    private final long expirationMinutes;
    private Connection dbConnection;
    private NDJSONSpoolWriter spoolWriter;

    public CorrelationStore(String dbPath, long expirationMinutes) {
        this(dbPath, expirationMinutes, null);
    }

    public CorrelationStore(String dbPath, long expirationMinutes, NDJSONSpoolWriter spoolWriter) {
        this.pendingTransactions = new ConcurrentHashMap<>();
        this.completedTransactions = Collections.synchronizedList(new ArrayList<>());
        this.expirationMinutes = expirationMinutes;
        this.spoolWriter = spoolWriter;
        initializeDatabase(dbPath);
    }

    private void initializeDatabase(String dbPath) {
        try {
            String url = "jdbc:sqlite:" + dbPath;
            dbConnection = DriverManager.getConnection(url);
            logger.info("Connected to SQLite database: {}", dbPath);

            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS tat_transactions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "correlation_key TEXT NOT NULL," +
                        "stan TEXT," +
                        "pan TEXT," +
                        "channel TEXT," +
                        "tat_millis INTEGER," +
                        "response_code TEXT," +
                        "is_valid BOOLEAN DEFAULT 1," +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "completed_at TIMESTAMP" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS exported_transactions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "correlation_key TEXT UNIQUE NOT NULL," +
                        "exported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS tat_metrics (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "metric_name TEXT NOT NULL," +
                        "metric_value REAL," +
                        "dimension_channel TEXT," +
                        "dimension_response_code TEXT," +
                        "recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")");

                logger.info("Database tables initialized");
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public void processMessage(ISO8583Message message) {
        String correlationKey = message.getCorrelationKey();

        if (!message.isValidMTI()) {
            logger.warn("Invalid MTI: {}. Only 1200 (request) and 1210 (response) supported.", message.getMessageId());
            return;
        }

        System.out.println("[DEBUG-CORR] Processing message. Key: " + correlationKey + ", MTI: " + message.getMessageId());

        if (message.isRequest()) {
            pendingTransactions.putIfAbsent(correlationKey, new TATTransaction(correlationKey, message));
            System.out.println("[DEBUG-CORR] Stored request (1200). Pending count: " + pendingTransactions.size());
            logger.debug("Stored request message: {} MTI=1200", correlationKey);
        } else if (message.isResponse()) {
            System.out.println("[DEBUG-CORR] Looking for matching request (1200)...");
            TATTransaction transaction = pendingTransactions.get(correlationKey);
            if (transaction != null) {
                transaction.setResponseMessage(message);
                long tatMs = transaction.getTatMillis();

                if (isValidTAT(tatMs)) {
                    System.out.println("[DEBUG-CORR] MATCHED! TAT = " + tatMs + " ms (VALID)");
                    moveToCompleted(transaction, true);
                    pendingTransactions.remove(correlationKey);
                    logger.info("Completed transaction: {} TAT={}ms (VALID)", correlationKey, tatMs);
                } else {
                    System.out.println("[DEBUG-CORR] MATCHED! TAT = " + tatMs + " ms (INVALID - outside range 1-60000ms)");
                    moveToCompleted(transaction, false);
                    pendingTransactions.remove(correlationKey);
                    logger.warn("Invalid TAT: {} ms (out of range 1-60000ms)", tatMs);
                }
            } else {
                System.out.println("[DEBUG-CORR] NO MATCHING REQUEST (1200) FOUND");
                logger.warn("Response (1210) received without matching request (1200): {}", correlationKey);
            }
        }
    }

    private boolean isValidTAT(long tatMs) {
        return tatMs >= 1 && tatMs <= 60000;
    }

    private void moveToCompleted(TATTransaction transaction, boolean isValid) {
        completedTransactions.add(transaction);
        persistTransaction(transaction, isValid);

        // Emit to NDJSON spool if configured (durable event) — only valid transactions
        if (spoolWriter != null && isValid) {
            spoolWriter.writeTransaction(transaction);
        }
    }

    private void persistTransaction(TATTransaction transaction, boolean isValid) {
        try (PreparedStatement pstmt = dbConnection.prepareStatement(
                "INSERT INTO tat_transactions (correlation_key, stan, pan, channel, tat_millis, response_code, is_valid, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

            pstmt.setString(1, transaction.getCorrelationKey());
            pstmt.setString(2, transaction.getStan());
            pstmt.setString(3, transaction.getPan());
            pstmt.setString(4, transaction.getChannel());
            pstmt.setLong(5, transaction.getTatMillis());
            pstmt.setString(6, transaction.getResponseCode());
            pstmt.setBoolean(7, isValid);
            pstmt.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to persist transaction", e);
        }
    }

    public List<TATTransaction> getUnexportedTransactions() {
        List<TATTransaction> unexported = new ArrayList<>();
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT correlation_key FROM tat_transactions WHERE is_valid = 1 " +
                     "AND correlation_key NOT IN (SELECT correlation_key FROM exported_transactions) LIMIT 10000")) {

            Set<String> exportedKeys = new HashSet<>();
            while (rs.next()) {
                String key = rs.getString("correlation_key");
                exportedKeys.add(key);
            }

            for (TATTransaction txn : completedTransactions) {
                if (exportedKeys.contains(txn.getCorrelationKey())) {
                    unexported.add(txn);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get unexported transactions", e);
        }
        return unexported;
    }

    public void markAsExported(String correlationKey) {
        try (PreparedStatement pstmt = dbConnection.prepareStatement(
                "INSERT OR IGNORE INTO exported_transactions (correlation_key) VALUES (?)")) {
            pstmt.setString(1, correlationKey);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to mark transaction as exported: {}", correlationKey, e);
        }
    }

    public void cleanExpiredTransactions() {
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, TATTransaction> entry : pendingTransactions.entrySet()) {
            if (entry.getValue().isExpired(expirationMinutes)) {
                expiredKeys.add(entry.getKey());
            }
        }

        for (String key : expiredKeys) {
            TATTransaction orphaned = pendingTransactions.remove(key);
            // Treat timeout as negative TAT for visibility in metrics
            persistOrphanedTransaction(orphaned);
            logger.warn("Unmatched request (no response): {} after {}min",
                key, expirationMinutes);
        }
    }

    private void persistOrphanedTransaction(TATTransaction transaction) {
        try (PreparedStatement pstmt = dbConnection.prepareStatement(
                "INSERT INTO tat_transactions (correlation_key, stan, pan, channel, tat_millis, response_code, is_valid, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

            pstmt.setString(1, transaction.getCorrelationKey());
            pstmt.setString(2, transaction.getStan());
            pstmt.setString(3, transaction.getPan());
            pstmt.setString(4, transaction.getChannel());
            pstmt.setLong(5, -1); // -1 = unmatched/timeout
            pstmt.setString(6, "TIMEOUT");
            pstmt.setBoolean(7, false); // Invalid - no matching response
            pstmt.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to persist orphaned transaction", e);
        }
    }

    public List<TATTransaction> getCompletedTransactions() {
        return new ArrayList<>(completedTransactions);
    }

    public int getPendingTransactionCount() {
        return pendingTransactions.size();
    }

    public void setSpoolWriter(NDJSONSpoolWriter spoolWriter) {
        this.spoolWriter = spoolWriter;
    }

    public NDJSONSpoolWriter getSpoolWriter() {
        return spoolWriter;
    }

    public void close() {
        try {
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close();
                logger.info("Database connection closed");
            }
        } catch (SQLException e) {
            logger.error("Failed to close database connection", e);
        }

        if (spoolWriter != null) {
            spoolWriter.close();
        }
    }
}
