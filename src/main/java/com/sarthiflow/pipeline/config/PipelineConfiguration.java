package com.sarthiflow.pipeline.config;

import com.sarthiflow.pipeline.blueprint.BlueprintConfig;
import com.sarthiflow.pipeline.blueprint.MetricDefinition;
import com.sarthiflow.pipeline.blueprint.MetricSource;
import com.sarthiflow.pipeline.blueprint.MetricType;
import com.sarthiflow.pipeline.parse.EventParser;
import com.sarthiflow.pipeline.parse.Iso8583EventParser;
import com.sarthiflow.pipeline.parse.JsonEventParser;
import com.sarthiflow.pipeline.parse.RegexEventParser;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class PipelineConfiguration {
    private final String databasePath;
    private final String blueprintName;
    private final List<Path> inputPaths;
    private final String format;
    private final String mode;
    private final int workerCount;
    private final long pollIntervalMillis;
    private final BlueprintConfig blueprint;
    private final String regexPattern;
    private final Map<String, Integer> regexGroups;
    private final String otlpEndpoint;
    private final String otlpToken;
    private final String senderMode;
    private final long senderPollIntervalMillis;
    private final int senderBatchSize;

    private PipelineConfiguration(Config root) {
        Config app = root.getConfig("sarthiflow");
        Config reader = app.hasPath("reader") ? app.getConfig("reader") : ConfigFactory.empty();
        Config blueprintConfig = app.getConfig("blueprint");
        this.databasePath = app.hasPath("database-path") ? app.getString("database-path")
                : reader.hasPath("database-path") ? reader.getString("database-path")
                : app.hasPath("sender.database-path") ? app.getString("sender.database-path") : null;
        if (databasePath == null || databasePath.trim().isEmpty()) {
            throw new IllegalArgumentException("A shared database path is required");
        }
        this.blueprintName = blueprintConfig.getString("name");
        this.inputPaths = new ArrayList<>();
        if (reader.hasPath("inputs")) {
            for (String input : reader.getStringList("inputs")) {
                inputPaths.add(Paths.get(input).toAbsolutePath().normalize());
            }
        }
        this.format = (reader.hasPath("format") ? reader.getString("format") : "json").toLowerCase(Locale.ROOT);
        if (!Set.of("json", "regex", "iso8583").contains(format)) {
            throw new IllegalArgumentException("Reader format must be json, regex, or iso8583");
        }
        this.mode = reader.hasPath("mode") ? reader.getString("mode").toLowerCase(Locale.ROOT) : "batch";
        if (!Set.of("batch", "tail").contains(mode)) {
            throw new IllegalArgumentException("Reader mode must be batch or tail");
        }
        this.workerCount = reader.hasPath("workers") ? reader.getInt("workers") : 4;
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Reader worker count must be positive");
        }
        this.pollIntervalMillis = reader.hasPath("poll-interval-ms")
                ? reader.getLong("poll-interval-ms") : 1000L;
        if (pollIntervalMillis <= 0) {
            throw new IllegalArgumentException("Reader poll interval must be positive");
        }

        BlueprintConfig.Builder builder = new BlueprintConfig.Builder()
                .name(blueprintName)
                .correlationKeyField(blueprintConfig.getString("correlation-key-field"))
                .requestTypeField(blueprintConfig.getString("request-type-field"))
                .responseTypeField(blueprintConfig.getString("response-type-field"))
                .requestValue(blueprintConfig.getString("request-value"))
                .responseValue(blueprintConfig.getString("response-value"))
                .timestampField(blueprintConfig.getString("timestamp-field"))
                .dimensionFields(blueprintConfig.hasPath("dimensions")
                        ? blueprintConfig.getStringList("dimensions") : Collections.emptyList())
                .granularity(blueprintConfig.hasPath("granularity")
                        ? blueprintConfig.getString("granularity") : "1m")
                .correlationTimeout(Duration.ofSeconds(blueprintConfig.hasPath("correlation-timeout-seconds")
                        ? blueprintConfig.getLong("correlation-timeout-seconds") : 3600L));
        if (blueprintConfig.hasPath("response-status-field")) {
            builder.responseStatusField(blueprintConfig.getString("response-status-field"));
        }
        if (blueprintConfig.hasPath("success-values")) {
            builder.successValues(Set.copyOf(blueprintConfig.getStringList("success-values")));
        }
        if (blueprintConfig.hasPath("metrics")) {
            for (Config metric : blueprintConfig.getConfigList("metrics")) {
                builder.addMetric(new MetricDefinition(metric.getString("name"),
                    MetricType.valueOf(metric.getString("type").toUpperCase(Locale.ROOT)),
                    metric.hasPath("source")
                        ? MetricSource.valueOf(metric.getString("source").toUpperCase(Locale.ROOT))
                        : defaultMetricSource(metric.getString("type"))));
            }
        }
        this.blueprint = builder.build();

        this.regexPattern = reader.hasPath("regex.pattern") ? reader.getString("regex.pattern") : null;
        this.regexGroups = new LinkedHashMap<>();
        if (reader.hasPath("regex.groups")) {
            ConfigObject groups = reader.getObject("regex.groups");
            for (Map.Entry<String, ConfigValue> group : groups.entrySet()) {
                regexGroups.put(group.getKey(), ((Number) group.getValue().unwrapped()).intValue());
            }
        }
        Config sender = app.hasPath("sender") ? app.getConfig("sender") : ConfigFactory.empty();
        this.otlpEndpoint = sender.hasPath("endpoint") ? sender.getString("endpoint") : null;
        this.otlpToken = sender.hasPath("token-env")
                ? System.getenv(sender.getString("token-env")) : null;
        this.senderMode = sender.hasPath("mode") ? sender.getString("mode").toLowerCase(Locale.ROOT) : "batch";
        if (!Set.of("batch", "tail").contains(senderMode)) {
            throw new IllegalArgumentException("Sender mode must be batch or tail");
        }
        this.senderPollIntervalMillis = sender.hasPath("poll-interval-ms")
                ? sender.getLong("poll-interval-ms") : 5000L;
        if (senderPollIntervalMillis <= 0) {
            throw new IllegalArgumentException("Sender poll interval must be positive");
        }
        this.senderBatchSize = sender.hasPath("batch-size") ? sender.getInt("batch-size") : 10000;
        if (senderBatchSize <= 0) {
            throw new IllegalArgumentException("Sender batch size must be positive");
        }
    }

    public static PipelineConfiguration loadFile(Path path) {
        File file = path.toFile();
        if (!file.isFile()) {
            throw new IllegalArgumentException("Configuration file does not exist: " + path);
        }
        return new PipelineConfiguration(ConfigFactory.parseFile(file).resolve());
    }

    public static PipelineConfiguration fromConfig(Config config) {
        return new PipelineConfiguration(config.resolve());
    }

    public EventParser createParser() {
        switch (format) {
            case "iso8583": return new Iso8583EventParser();
            case "json": return new JsonEventParser();
            case "regex":
                if (regexPattern == null || regexGroups.isEmpty()) {
                    throw new IllegalArgumentException("Regex format requires reader.regex.pattern and reader.regex.groups");
                }
                return new RegexEventParser(Pattern.compile(regexPattern), regexGroups);
            default: throw new IllegalStateException("Unsupported reader format: " + format);
        }
    }

    public String getDatabasePath() { return databasePath; }
    public String getBlueprintName() { return blueprintName; }
    public List<Path> getInputPaths() { return Collections.unmodifiableList(inputPaths); }
    public String getFormat() { return format; }
    public String getMode() { return mode; }
    public int getWorkerCount() { return workerCount; }
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public BlueprintConfig getBlueprint() { return blueprint; }
    public String getOtlpEndpoint() { return otlpEndpoint; }
    public String getOtlpToken() { return otlpToken; }
    public String getSenderMode() { return senderMode; }
    public long getSenderPollIntervalMillis() { return senderPollIntervalMillis; }
    public int getSenderBatchSize() { return senderBatchSize; }

    private MetricSource defaultMetricSource(String type) {
        switch (MetricType.valueOf(type.toUpperCase(Locale.ROOT))) {
            case HISTOGRAM: return MetricSource.LATENCY_MS;
            case COUNTER: return MetricSource.COUNT;
            case RATE: return MetricSource.THROUGHPUT_PER_SECOND;
            case GAUGE: return MetricSource.AVG_LATENCY_MS;
            default: throw new IllegalArgumentException("Unsupported metric type: " + type);
        }
    }
}