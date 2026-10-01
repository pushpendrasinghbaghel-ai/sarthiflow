package com.sarthiflow.file;

import com.sarthiflow.model.ISO8583Message;
import com.sarthiflow.parser.ISO8583Parser;
import com.sarthiflow.store.CorrelationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ResumableFileReader {
    private static final Logger logger = LoggerFactory.getLogger(ResumableFileReader.class);

    private final FileOffsetTracker offsetTracker;
    private final ISO8583Parser parser;
    private final CorrelationStore correlationStore;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public ResumableFileReader(FileOffsetTracker offsetTracker, ISO8583Parser parser,
                              CorrelationStore correlationStore) {
        this.offsetTracker = offsetTracker;
        this.parser = parser;
        this.correlationStore = correlationStore;
    }

    public void processAllLogFiles(Path logDirectory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDirectory, "CIDC_*.txt")) {
            for (Path logFile : stream) {
                executor.submit(() -> processFileWithOffsetTracking(logFile));
            }
        }
    }

    private void processFileWithOffsetTracking(Path logFile) {
        try {
            System.out.println("[DEBUG] Processing file: " + logFile.getFileName());
            // Step 1: Get safe read position
            long lastReadOffset = offsetTracker.getSafeReadOffset(logFile);
            long fileSize = Files.size(logFile);

            System.out.println("[DEBUG] File size: " + fileSize + ", Last offset: " + lastReadOffset);

            if (lastReadOffset >= fileSize) {
                System.out.println("[DEBUG] No new content in " + logFile.getFileName());
                logger.debug("No new content in {}", logFile.getFileName());
                return;
            }

            System.out.println("[DEBUG] Reading " + (fileSize - lastReadOffset) + " bytes new");
            logger.info("Reading {} from byte {} to {} ({} bytes new)",
                logFile.getFileName(),
                lastReadOffset,
                fileSize,
                fileSize - lastReadOffset);

            // Step 2: Read only new content
            String newContent = readNewContent(logFile, lastReadOffset);

            if (newContent.isEmpty()) {
                logger.debug("No readable content from {} at offset {}",
                    logFile.getFileName(), lastReadOffset);
                return;
            }

            // Step 3: Process messages
            List<ISO8583Message> messagesRead = new ArrayList<>();
            String[] blocks = newContent.split("<=========>");
            System.out.println("[DEBUG] Found " + blocks.length + " message blocks");

            for (String block : blocks) {
                if (block.trim().isEmpty()) continue;

                try {
                    System.out.println("[DEBUG] Parsing message block...");
                    ISO8583Message msg = parser.parseMessage(block);
                    System.out.println("[DEBUG] Parsed message ID: " + msg.getMessageId() + ", Direction: " + msg.getDirection());
                    if (msg.getMessageId() != null) {
                        correlationStore.processMessage(msg);
                        messagesRead.add(msg);
                        System.out.println("[DEBUG] Message processed successfully");
                    }
                } catch (Exception e) {
                    System.out.println("[DEBUG] ERROR parsing message: " + e.getMessage());
                    logger.error("Failed to parse message block in {}", logFile.getFileName(), e);
                    // Continue processing other blocks
                }
            }

            System.out.println("[DEBUG] Processed " + messagesRead.size() + " messages total");
            logger.info("Successfully processed {} messages from {}",
                messagesRead.size(), logFile.getFileName());

            // Step 4: Update offset ONLY after all successful processing
            offsetTracker.updateOffset(logFile, fileSize);

        } catch (Exception e) {
            logger.error("Error processing file: {}", logFile, e);
            // DO NOT update offset on error - will retry next run
        }
    }

    private String readNewContent(Path file, long offset) throws IOException {
        long fileSize = Files.size(file);

        if (offset >= fileSize) {
            return "";
        }

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);

            StringBuilder content = new StringBuilder();
            String line;

            while ((line = raf.readLine()) != null) {
                content.append(line).append("\n");
            }

            return content.toString();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

