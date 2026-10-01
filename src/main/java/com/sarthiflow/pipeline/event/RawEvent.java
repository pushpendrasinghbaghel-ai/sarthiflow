package com.sarthiflow.pipeline.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RawEvent {
    private final Map<String, String> fields;

    public RawEvent(Map<String, ?> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("Event fields are required");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(key, String.valueOf(value));
            }
        });
        this.fields = Collections.unmodifiableMap(normalized);
    }

    public String get(String field) {
        return fields.get(field);
    }

    public Map<String, String> getFields() {
        return fields;
    }
}