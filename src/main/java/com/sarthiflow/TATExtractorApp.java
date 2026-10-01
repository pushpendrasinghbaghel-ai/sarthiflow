package com.sarthiflow;

import com.sarthiflow.file.ContinuousFileWatcher;
import com.sarthiflow.file.FileOffsetTracker;
import com.sarthiflow.file.ResumableFileReader;
import com.sarthiflow.metrics.NDJSONSpoolWriter;
import com.sarthiflow.metrics.OTLPExporter;
import com.sarthiflow.metrics.TATMetricsExporter;
import com.sarthiflow.parser.ISO8583Parser;
import com.sarthiflow.store.CorrelationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TATExtractorApp {
    private static final Logger logger = LoggerFactory.getLogger(TATExtractorApp.class);

    private CorrelationStore correlationStore;
    private TATMetricsExporter metricsExporter;
    private OTLPExporter otlpExporter;
    private NDJSONSpoolWriter spoolWriter;
    private ISO8583Parser parser;
    private ScheduledExecutorService scheduler;
    private Configuration config;
    private FileOffsetTracker offsetTracker;
    private ContinuousFileWatcher fileWatcher;

    public static void main(String[] args) {
        TATExtractorApp app = new TATExtractorApp();
        app.run(args);
    }

    private void run(String[] args) {
        System.out.println("[DEBUG] TAT Extractor starting...");
        try {
            // Load configuration
            config = Configuration.loadFromFile(getConfigPath(args));
            System.out.println("[DEBUG] Configuration loaded");
            System.out.println("[DEBUG] Log path: " + config.getLogFilePath());
            logger.info("Configuration loaded: {}", config);
            logExportConfiguration();
            System.out.println("[DEBUG] Export configuration logged");

            // Initialize spool writer if enabled
            if (config.isUseSpool()) {
                logger.info("Initializing NDJSON spool writer at: {}", config.getSpoolPath());
                this.spoolWriter = new NDJSONSpoolWriter(config.getSpoolPath());
            }

            // Initialize components
            this.correlationStore = new CorrelationStore(
                    config.getDbPath(),
                    config.getExpirationMinutes(),
                    spoolWriter
            );
            this.parser = new ISO8583Parser();

            // FIX #6: Initialize OTLP exporter ONLY if enabled in config
            if (config.isUseOTLPDirect()) {
                System.out.println("[APP] Initializing OTLP exporter");
                this.otlpExporter = new OTLPExporter(
                        correlationStore,
                        config.getBindplaneUrl(),
                        config.getApiToken()
                );
            } else {
                System.out.println("[APP] OTLP direct export disabled in config");
                this.otlpExporter = null;
            }

            // Initialize JSON exporter as fallback (legacy)
            this.metricsExporter = new TATMetricsExporter(
                    correlationStore,
                    config.getBindplaneUrl()
            );

            // Initialize file reading components
            this.offsetTracker = new FileOffsetTracker(
                    config.getOffsetDbPath()
            );

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            // Initialize scheduler
            this.scheduler = Executors.newScheduledThreadPool(4);

            // Start continuous file watcher
            ResumableFileReader fileReader = new ResumableFileReader(
                    offsetTracker,
                    parser,
                    correlationStore
            );

            // Create exporter wrapper that delegates to appropriate exporter(s)
            TATMetricsExporter exporter = new TATMetricsExporter(correlationStore, config.getBindplaneUrl()) {
                @Override
                public void exportMetrics() {
                    // Export via OTLP binary protocol
                    if (otlpExporter != null) {
                        otlpExporter.exportMetrics();
                    }
                    // Export to spool if enabled (for Bindplane or custom readers)
                    if (config.isUseSpool()) {
                        logger.debug("Spool contains {} pending events",
                                spoolWriter.getSpoolFileSize());
                    }
                }
            };

            this.fileWatcher = new ContinuousFileWatcher(
                    fileReader,
                    correlationStore,
                    exporter,
                    scheduler,
                    Paths.get(config.getLogFilePath()),
                    10,  // Scan every 10 seconds
                    config.getExportIntervalSeconds()  // Use configured export interval
            );

            logger.info("Starting TAT Extractor in {} mode",
                    config.isWatchMode() ? "WATCH" : "BATCH");
            fileWatcher.startWatching();

            // Keep app running
            try {
                scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                logger.info("Application interrupted");
                Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            logger.error("Application error", e);
            System.exit(1);
        }
    }


    private String getConfigPath(String[] args) {
        if (args.length > 0) {
            return args[0];
        }

        // Try common locations for external config
        String[] locations = {
            "application.conf",
            System.getProperty("user.dir") + "/application.conf",
            System.getProperty("user.home") + "/iso8583/application.conf",
            "/etc/iso8583/application.conf"
        };

        for (String location : locations) {
            java.io.File f = new java.io.File(location);
            if (f.exists()) {
                System.out.println("[CONFIG] Found config file at: " + location);
                return location;
            }
        }

        System.out.println("[CONFIG] No external config found, will use defaults or environment variables");
        return "application.conf"; // Fallback to search in classpath
    }

    private void shutdown() {
        logger.info("Shutting down application...");

        if (fileWatcher != null) {
            fileWatcher.shutdown();
        }

        // Export final metrics based on configuration
        if (config != null) {
            if (otlpExporter != null) {
                logger.info("Exporting final metrics via OTLP");
                otlpExporter.exportMetrics();
                otlpExporter.close();
            }
            if (config.isUseSpool() && spoolWriter != null) {
                logger.info("Final spool size: {} bytes", spoolWriter.getSpoolFileSize());
            }
        }

        if (offsetTracker != null) {
            offsetTracker.close();
        }

        if (correlationStore != null) {
            correlationStore.close();
        }

        if (spoolWriter != null) {
            spoolWriter.close();
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        logger.info("Shutdown complete");
    }

    private void logExportConfiguration() {
        logger.info("=== Export Configuration ===");
        logger.info("OTLP Direct Export: {}", config.isUseOTLPDirect());
        logger.info("NDJSON Spool Writer: {}", config.isUseSpool());
        logger.info("Bindplane Integration: {}", config.isUseBindplane());
        if (config.isUseSpool()) {
            logger.info("Spool Path: {}", config.getSpoolPath());
        }
        logger.info("============================");
    }
}

