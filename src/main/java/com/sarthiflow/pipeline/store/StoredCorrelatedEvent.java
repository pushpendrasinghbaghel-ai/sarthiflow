package com.sarthiflow.pipeline.store;

import com.sarthiflow.pipeline.correlation.CorrelatedEvent;

public final class StoredCorrelatedEvent {
    private final long id;
    private final CorrelatedEvent event;

    public StoredCorrelatedEvent(long id, CorrelatedEvent event) {
        this.id = id;
        this.event = event;
    }

    public long getId() { return id; }
    public CorrelatedEvent getEvent() { return event; }
}