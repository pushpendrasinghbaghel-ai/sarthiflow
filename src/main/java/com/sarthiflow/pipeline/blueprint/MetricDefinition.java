package com.sarthiflow.pipeline.blueprint;

import java.util.Objects;

public final class MetricDefinition {
    private final String name;
    private final MetricType type;
    private final MetricSource source;

    public MetricDefinition(String name, MetricType type) {
        this(name, type, defaultSource(type));
    }

    public MetricDefinition(String name, MetricType type, MetricSource source) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Metric name is required");
        }
        this.name = name.trim();
        this.type = Objects.requireNonNull(type, "Metric type is required");
        this.source = Objects.requireNonNull(source, "Metric source is required");
        if (type == MetricType.HISTOGRAM && source != MetricSource.LATENCY_MS) {
            throw new IllegalArgumentException("Histogram source must be LATENCY_MS");
        }
        if (type == MetricType.COUNTER && source != MetricSource.COUNT
                && source != MetricSource.SUCCESS_COUNT && source != MetricSource.ERROR_COUNT) {
            throw new IllegalArgumentException("Counter source must be COUNT, SUCCESS_COUNT, or ERROR_COUNT");
        }
        if (type == MetricType.RATE && source != MetricSource.SUCCESS_RATE
                && source != MetricSource.ERROR_RATE && source != MetricSource.THROUGHPUT_PER_SECOND) {
            throw new IllegalArgumentException("Rate source must be a rate or throughput value");
        }
    }

    private static MetricSource defaultSource(MetricType type) {
        Objects.requireNonNull(type, "Metric type is required");
        switch (type) {
            case HISTOGRAM: return MetricSource.LATENCY_MS;
            case COUNTER: return MetricSource.COUNT;
            case RATE: return MetricSource.THROUGHPUT_PER_SECOND;
            case GAUGE: return MetricSource.AVG_LATENCY_MS;
            default: throw new IllegalArgumentException("Unsupported metric type: " + type);
        }
    }

    public String getName() {
        return name;
    }

    public MetricType getType() {
        return type;
    }

    public MetricSource getSource() {
        return source;
    }
}