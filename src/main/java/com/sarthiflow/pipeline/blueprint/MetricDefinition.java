package com.sarthiflow.pipeline.blueprint;

import java.util.Objects;

public final class MetricDefinition {
    private final String name;
    private final MetricType type;

    public MetricDefinition(String name, MetricType type) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Metric name is required");
        }
        this.name = name.trim();
        this.type = Objects.requireNonNull(type, "Metric type is required");
    }

    public String getName() {
        return name;
    }

    public MetricType getType() {
        return type;
    }
}