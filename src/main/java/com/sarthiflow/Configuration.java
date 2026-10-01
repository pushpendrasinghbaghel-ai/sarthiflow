package com.sarthiflow;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;

public class Configuration {
    private static final Logger logger = LoggerFactory.getLogger(Configuration.class);

    private String logFilePath;
    private String dbPath;
    private String offsetDbPath;
    private String bindplaneUrl;
    private String apiToken;
    private boolean useOTLPDirect;
    private boolean useSpool;
    private boolean useBindplane;
    private String spoolPath;
    private long expirationMinutes;
    private long exportIntervalSeconds;
    private boolean watchMode;

    private Configuration() {}

    public static Configuration loadFromFile(String configPath) {
        Configuration config = new Configuration();

        try {
            Config hocon;
            File configFile = new File(configPath);

            // Priority 1: File system config (external file)
            if (configFile.exists()) {
                logger.info("Loading configuration from file: {}", configPath);
                hocon = ConfigFactory.parseFile(configFile);
            } else {
                logger.info("File not found at: {}", configPath);
                hocon = ConfigFactory.empty();
            }

            // Priority 2: System properties (e.g., -Diso8583.api.token=...)
            // Priority 3: Environment variables
            Config sysConfig = ConfigFactory.systemProperties().withFallback(ConfigFactory.systemEnvironment());

            // Priority 4: Defaults
            Config defaultConfig = ConfigFactory.parseString(getDefaultConfig());

            // Merge in priority order
            Config finalConfig = sysConfig
                    .withFallback(hocon)
                    .withFallback(defaultConfig);

            config.logFilePath = finalConfig.getString("iso8583.log.path");
            config.dbPath = finalConfig.getString("iso8583.db.path");
            config.offsetDbPath = finalConfig.getString("iso8583.offset.db.path");
            config.bindplaneUrl = finalConfig.getString("iso8583.bindplane.url");
            config.apiToken = finalConfig.getString("iso8583.api.token");
            config.useOTLPDirect = finalConfig.getBoolean("iso8583.export.use.otlp.direct");
            config.useSpool = finalConfig.getBoolean("iso8583.export.use.spool");
            config.useBindplane = finalConfig.getBoolean("iso8583.export.use.bindplane");
            config.spoolPath = finalConfig.getString("iso8583.spool.path");
            config.expirationMinutes = finalConfig.getLong("iso8583.correlation.expiration.minutes");
            config.exportIntervalSeconds = finalConfig.getLong("iso8583.export.interval.seconds");
            config.watchMode = finalConfig.getBoolean("iso8583.watch.enabled");

            logger.info("Configuration loaded successfully: {}", config);
            return config;
        } catch (Exception e) {
            logger.error("Failed to load configuration, using defaults", e);
            return config.applyDefaults();
        }
    }

    private static String getDefaultConfig() {
        return "iso8583 {\n" +
                "  log.path = \"C:\\var\\log\\payment\\\"\n" +
                "  db.path = \"C:\\var\\iso8583\\correlation.db\"\n" +
                "  offset.db.path = \"C:\\var\\iso8583\\file_offsets.db\"\n" +
                "  bindplane.url = \"https://fhq90909.live.dynatrace.com/api/v2/otlp/v1/metrics\"\n" +
                "  api.token = \"dt0c01.XXXX.YYYY\"\n" +
                "  export.use.otlp.direct = true\n" +
                "  export.use.spool = false\n" +
                "  export.use.bindplane = false\n" +
                "  spool.path = \"C:\\var\\iso8583\\spool\\\"\n" +
                "  correlation.expiration.minutes = 5\n" +
                "  export.interval.seconds = 30\n" +
                "  watch.enabled = true\n" +
                "}\n";
    }

    private Configuration applyDefaults() {
        this.logFilePath = "C:\\var\\log\\payment\\";
        this.dbPath = "C:\\var\\iso8583\\correlation.db";
        this.offsetDbPath = "C:\\var\\iso8583\\file_offsets.db";
        this.bindplaneUrl = "https://fhq90909.live.dynatrace.com/api/v2/otlp/v1/metrics";
        this.apiToken = "dt0c01.XXXX.YYYY";
        this.useOTLPDirect = true;
        this.useSpool = false;
        this.useBindplane = false;
        this.spoolPath = "C:\\var\\iso8583\\spool\\";
        this.expirationMinutes = 5;
        this.exportIntervalSeconds = 30;
        this.watchMode = true;
        return this;
    }

    public String getLogFilePath() {
        return logFilePath;
    }

    public String getDbPath() {
        return dbPath;
    }

    public String getOffsetDbPath() {
        return offsetDbPath;
    }

    public String getBindplaneUrl() {
        return bindplaneUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public boolean isUseOTLPDirect() {
        return useOTLPDirect;
    }

    public boolean isUseSpool() {
        return useSpool;
    }

    public boolean isUseBindplane() {
        return useBindplane;
    }

    public String getSpoolPath() {
        return spoolPath;
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    public long getExportIntervalSeconds() {
        return exportIntervalSeconds;
    }

    public boolean isWatchMode() {
        return watchMode;
    }

    @Override
    public String toString() {
        return "Configuration{" +
                "logFilePath='" + logFilePath + '\'' +
                ", dbPath='" + dbPath + '\'' +
                ", bindplaneUrl='" + bindplaneUrl + '\'' +
                ", expirationMinutes=" + expirationMinutes +
                ", exportIntervalSeconds=" + exportIntervalSeconds +
                ", watchMode=" + watchMode +
                '}';
    }
}

