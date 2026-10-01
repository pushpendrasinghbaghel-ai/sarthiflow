package com.sarthiflow.pipeline.correlation;

import com.sarthiflow.pipeline.blueprint.BlueprintConfig;
import com.sarthiflow.pipeline.event.RawEvent;
import com.sarthiflow.pipeline.store.StoredCorrelatedPair;
import com.sarthiflow.pipeline.store.StoredRawEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CorrelationEngine {
    private final BlueprintConfig config;

    public CorrelationEngine(BlueprintConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Blueprint configuration is required");
        }
        this.config = config;
    }

    public List<CorrelatedEvent> correlate(List<RawEvent> events) {
        if (events == null || events.isEmpty()) {
            return new ArrayList<>();
        }

        List<TimedEvent> ordered = new ArrayList<>();
        for (RawEvent event : events) {
            if (event == null) {
                continue;
            }
            Instant timestamp = parseTimestamp(event.get(config.getTimestampField()));
            String key = event.get(config.getCorrelationKeyField());
            if (timestamp != null && key != null && !key.trim().isEmpty()) {
                ordered.add(new TimedEvent(event, timestamp, key));
            }
        }
        ordered.sort(Comparator.comparing((TimedEvent item) -> item.timestamp)
            .thenComparingInt(item -> config.getRequestValue().equals(
                item.event.get(config.getRequestTypeField())) ? 0 : 1));

        Map<String, TimedEvent> pending = new HashMap<>();
        List<CorrelatedEvent> correlated = new ArrayList<>();
        Duration timeout = config.getCorrelationTimeout();
        for (TimedEvent current : ordered) {
            pending.entrySet().removeIf(entry ->
                    Duration.between(entry.getValue().timestamp, current.timestamp).compareTo(timeout) > 0);
            String type = current.event.get(config.getRequestTypeField());
            if (config.getRequestValue().equals(type)) {
                pending.putIfAbsent(current.key, current);
            } else if (config.getResponseValue().equals(current.event.get(config.getResponseTypeField()))) {
                TimedEvent request = pending.remove(current.key);
                if (request == null) {
                    continue;
                }
                Duration elapsed = Duration.between(request.timestamp, current.timestamp);
                if (elapsed.isNegative() || elapsed.compareTo(timeout) > 0) {
                    continue;
                }
                correlated.add(toCorrelatedEvent(request.event, current.event, elapsed.toMillis(),
                    request.timestamp, current.timestamp));
            }
        }
        return correlated;
    }

    public List<StoredCorrelatedPair> correlateStored(List<StoredRawEvent> events) {
        if (events == null || events.isEmpty()) {
            return new ArrayList<>();
        }
        List<RawEvent> rawEvents = new ArrayList<>(events.size());
        Map<RawEvent, StoredRawEvent> storedByIdentity = new IdentityHashMap<>();
        for (StoredRawEvent stored : events) {
            rawEvents.add(stored.getEvent());
            storedByIdentity.put(stored.getEvent(), stored);
        }

        List<StoredCorrelatedPair> pairs = new ArrayList<>();
        for (CorrelatedEvent event : correlate(rawEvents)) {
            StoredRawEvent request = storedByIdentity.get(event.getRequest());
            StoredRawEvent response = storedByIdentity.get(event.getResponse());
            if (request != null && response != null) {
                pairs.add(new StoredCorrelatedPair(request, response, event));
            }
        }
        return pairs;
    }

    private CorrelatedEvent toCorrelatedEvent(RawEvent request, RawEvent response, long latencyMs,
                                              Instant requestTimestamp, Instant responseTimestamp) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (String field : config.getDimensionFields()) {
            String value = response.get(field);
            if (value == null) {
                value = request.get(field);
            }
            if (value != null) {
                dimensions.put(field, value);
            }
        }

        String outcome = "UNKNOWN";
        String statusField = config.getResponseStatusField();
        if (statusField != null) {
            String status = response.get(statusField);
            if (status != null) {
                outcome = config.getSuccessValues().contains(status) ? "SUCCESS" : "ERROR";
            }
        }
        return new CorrelatedEvent(request, response, latencyMs, outcome, dimensions,
            requestTimestamp, responseTimestamp);
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static final class TimedEvent {
        private final RawEvent event;
        private final Instant timestamp;
        private final String key;

        private TimedEvent(RawEvent event, Instant timestamp, String key) {
            this.event = event;
            this.timestamp = timestamp;
            this.key = key;
        }
    }
}