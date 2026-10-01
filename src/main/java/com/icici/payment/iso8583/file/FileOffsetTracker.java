package com.icici.payment.iso8583.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FileOffsetTracker {
    private static final Logger logger = LoggerFactory.getLogger(FileOffsetTracker.class);
    private Connection db;

    public FileOffsetTracker(String offsetDbPath) {
        initializeDatabase(offsetDbPath);
    }

    private void initializeDatabase(String offsetDbPath) {
        try {
            String url = "jdbc:sqlite:" + offsetDbPath;
            db = DriverManager.getConnection(url);
            logger.info("Connected to offset tracking database: {}", offsetDbPath);

            try (Statement stmt = db.createStatement()) {
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS file_offsets (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  file_path TEXT UNIQUE NOT NULL," +
                    "  last_offset BIGINT NOT NULL," +
                    "  last_file_size BIGINT NOT NULL," +
                    "  last_modified TIMESTAMP NOT NULL," +
                    "  last_read_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")"
                );
                logger.info("Offset tracking table initialized");
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize offset tracker database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public long getSafeReadOffset(Path file) throws IOException {
        long currentSize = Files.size(file);

        try (PreparedStatement stmt = db.prepareStatement(
             "SELECT last_offset, last_file_size FROM file_offsets WHERE file_path = ?")) {

            stmt.setString(1, file.toString());
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                logger.debug("File never read before: {}", file.getFileName());
                return 0;
            }

            long lastOffset = rs.getLong("last_offset");
            long lastSize = rs.getLong("last_file_size");

            if (currentSize < lastOffset) {
                logger.info("File rotated detected: {} (size {} → {}). Starting from 0",
                    file.getFileName(), lastSize, currentSize);
                return 0;
            }

            logger.debug("Safe read offset for {}: {} bytes", file.getFileName(), lastOffset);
            return lastOffset;

        } catch (SQLException e) {
            logger.error("Failed to get safe read offset", e);
            return 0;
        }
    }

    public void updateOffset(Path file, long newOffset) throws IOException {
        long fileSize = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();

        try (PreparedStatement stmt = db.prepareStatement(
             "INSERT OR REPLACE INTO file_offsets " +
             "(file_path, last_offset, last_file_size, last_modified) " +
             "VALUES (?, ?, ?, ?)")) {

            stmt.setString(1, file.toString());
            stmt.setLong(2, newOffset);
            stmt.setLong(3, fileSize);
            stmt.setLong(4, lastModified);
            stmt.executeUpdate();

            logger.debug("Updated offset for {}: {} bytes", file.getFileName(), newOffset);

        } catch (SQLException e) {
            logger.error("Failed to update offset", e);
        }
    }

    public List<Path> getTrackedFiles() throws SQLException {
        List<Path> files = new ArrayList<>();

        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT file_path FROM file_offsets")) {

            while (rs.next()) {
                files.add(Path.of(rs.getString("file_path")));
            }
        }

        return files;
    }

    public void close() {
        try {
            if (db != null && !db.isClosed()) {
                db.close();
                logger.info("Offset tracker database connection closed");
            }
        } catch (SQLException e) {
            logger.error("Failed to close offset tracker database", e);
        }
    }
}
