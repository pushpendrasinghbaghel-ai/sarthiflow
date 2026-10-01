package com.sarthiflow.pipeline.store;

import com.sarthiflow.pipeline.event.RawEvent;

import java.time.Instant;

public final class StoredRawEvent {
    private final long id;
    private final String correlationKey;
    private final String eventType;
    private final Instant timestamp;
    private final RawEvent event;

    public StoredRawEvent(long id, String correlationKey, String eventType, Instant timestamp, RawEvent event) {
        this.id = id;
        this.correlationKey = correlationKey;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.event = event;
    }

    public long getId() { return id; }
    public String getCorrelationKey() { return correlationKey; }
    public String getEventType() { return eventType; }
    public Instant getTimestamp() { return timestamp; }
    public RawEvent getEvent() { return event; }
}