package com.sarthiflow.pipeline.store;

import com.sarthiflow.pipeline.correlation.CorrelatedEvent;

public final class StoredCorrelatedPair {
    private final StoredRawEvent request;
    private final StoredRawEvent response;
    private final CorrelatedEvent event;

    public StoredCorrelatedPair(StoredRawEvent request, StoredRawEvent response, CorrelatedEvent event) {
        this.request = request;
        this.response = response;
        this.event = event;
    }

    public StoredRawEvent getRequest() { return request; }
    public StoredRawEvent getResponse() { return response; }
    public CorrelatedEvent getEvent() { return event; }
}