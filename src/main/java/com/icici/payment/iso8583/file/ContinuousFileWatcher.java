package com.icici.payment.iso8583.file;

import com.icici.payment.iso8583.metrics.TATMetricsExporter;
import com.icici.payment.iso8583.store.CorrelationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ContinuousFileWatcher {
    private static final Logger logger = LoggerFactory.getLogger(ContinuousFileWatcher.class);

    private final ResumableFileReader fileReader;
    private final CorrelationStore correlationStore;
    private final TATMetricsExporter metricsExporter;
    private final ScheduledExecutorService scheduler;
    private final Path logDirectory;
    private final long scanIntervalSeconds;
    private final long exportIntervalSeconds;

    public ContinuousFileWatcher(ResumableFileReader fileReader,
                                CorrelationStore correlationStore,
                                TATMetricsExporter metricsExporter,
                                ScheduledExecutorService scheduler,
                                Path logDirectory,
                                long scanIntervalSeconds,
                                long exportIntervalSeconds) {
        this.fileReader = fileReader;
        this.correlationStore = correlationStore;
        this.metricsExporter = metricsExporter;
        this.scheduler = scheduler;
        this.logDirectory = logDirectory;
        this.scanIntervalSeconds = scanIntervalSeconds;
        this.exportIntervalSeconds = exportIntervalSeconds;
    }

    public void startWatching() {
        logger.info("Starting continuous file watcher for directory: {}", logDirectory);

        // Scan directory at specified interval (default 10 seconds)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                fileReader.processAllLogFiles(logDirectory);
            } catch (IOException e) {
                logger.error("Error scanning log directory: {}", logDirectory, e);
            }
        }, 0, scanIntervalSeconds, TimeUnit.SECONDS);

        logger.info("File scan scheduled every {} seconds", scanIntervalSeconds);

        // Export metrics at configured interval
        scheduler.scheduleAtFixedRate(() -> {
            try {
                metricsExporter.exportMetrics();
            } catch (Exception e) {
                logger.error("Error exporting metrics", e);
            }
        }, 0, exportIntervalSeconds, TimeUnit.SECONDS);

        logger.info("Metrics export scheduled every {} seconds", exportIntervalSeconds);

        // Cleanup expired transactions every 5 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                correlationStore.cleanExpiredTransactions();
            } catch (Exception e) {
                logger.error("Error cleaning expired transactions", e);
            }
        }, 1, 5, TimeUnit.MINUTES);

        logger.info("Transaction cleanup scheduled every 5 minutes");
    }

    public void shutdown() {
        logger.info("Shutting down continuous file watcher...");
        fileReader.shutdown();
    }
}
