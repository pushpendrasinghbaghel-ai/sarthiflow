package com.sarthiflow.pipeline.blueprint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BlueprintConfig {
    private final String name;
    private final String correlationKeyField;
    private final String requestTypeField;
    private final String responseTypeField;
    private final String requestValue;
    private final String responseValue;
    private final String timestampField;
    private final List<String> dimensionFields;
    private final String granularity;
    private final Duration correlationTimeout;
    private final String responseStatusField;
    private final Set<String> successValues;
    private final List<MetricDefinition> metrics;

    private BlueprintConfig(Builder builder) {
        this.name = requireText(builder.name, "Blueprint name");
        this.correlationKeyField = requireText(builder.correlationKeyField, "Correlation key field");
        this.requestTypeField = requireText(builder.requestTypeField, "Request type field");
        this.responseTypeField = requireText(builder.responseTypeField, "Response type field");
        this.requestValue = requireText(builder.requestValue, "Request value");
        this.responseValue = requireText(builder.responseValue, "Response value");
        this.timestampField = requireText(builder.timestampField, "Timestamp field");
        this.dimensionFields = Collections.unmodifiableList(new ArrayList<>(builder.dimensionFields));
        this.granularity = requireText(builder.granularity, "Aggregation granularity");
        this.correlationTimeout = builder.correlationTimeout;
        this.responseStatusField = builder.responseStatusField;
        this.successValues = Collections.unmodifiableSet(new LinkedHashSet<>(builder.successValues));
        this.metrics = Collections.unmodifiableList(new ArrayList<>(builder.metrics));
    }

    public static final class Builder {
        private String name;
        private String correlationKeyField;
        private String requestTypeField;
        private String responseTypeField;
        private String requestValue;
        private String responseValue;
        private String timestampField;
        private List<String> dimensionFields = Collections.emptyList();
        private String granularity = "1m";
        private Duration correlationTimeout = Duration.ofMinutes(60);
        private String responseStatusField;
        private Set<String> successValues = Collections.emptySet();
        private List<MetricDefinition> metrics = Collections.emptyList();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder correlationKeyField(String field) {
            this.correlationKeyField = field;
            return this;
        }

        public Builder requestTypeField(String field) {
            this.requestTypeField = field;
            return this;
        }

        public Builder responseTypeField(String field) {
            this.responseTypeField = field;
            return this;
        }

        public Builder requestValue(String value) {
            this.requestValue = value;
            return this;
        }

        public Builder responseValue(String value) {
            this.responseValue = value;
            return this;
        }

        public Builder timestampField(String field) {
            this.timestampField = field;
            return this;
        }

        public Builder dimensionFields(List<String> fields) {
            this.dimensionFields = fields == null ? Collections.emptyList() : fields;
            return this;
        }

        public Builder granularity(String granularity) {
            this.granularity = granularity;
            return this;
        }

        public Builder correlationTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Correlation timeout must be positive");
            }
            this.correlationTimeout = timeout;
            return this;
        }

        public Builder responseStatusField(String field) {
            this.responseStatusField = field;
            return this;
        }

        public Builder successValues(Set<String> values) {
            this.successValues = values == null ? Collections.emptySet() : values;
            return this;
        }

        public Builder addMetric(MetricDefinition metric) {
            List<MetricDefinition> updated = new ArrayList<>(metrics);
            updated.add(metric);
            metrics = updated;
            return this;
        }

        public BlueprintConfig build() {
            return new BlueprintConfig(this);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    public String getName() { return name; }
    public String getCorrelationKeyField() { return correlationKeyField; }
    public String getRequestTypeField() { return requestTypeField; }
    public String getResponseTypeField() { return responseTypeField; }
    public String getRequestValue() { return requestValue; }
    public String getResponseValue() { return responseValue; }
    public String getTimestampField() { return timestampField; }
    public List<String> getDimensionFields() { return dimensionFields; }
    public String getGranularity() { return granularity; }
    public Duration getCorrelationTimeout() { return correlationTimeout; }
    public String getResponseStatusField() { return responseStatusField; }
    public Set<String> getSuccessValues() { return successValues; }
    public List<MetricDefinition> getMetrics() { return metrics; }
}