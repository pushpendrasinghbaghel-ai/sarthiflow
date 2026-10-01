package com.sarthiflow.pipeline.correlation;

import com.sarthiflow.pipeline.event.RawEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CorrelatedEvent {
    private final RawEvent request;
    private final RawEvent response;
    private final long latencyMs;
    private final String outcome;
    private final Map<String, String> dimensions;
    private final Instant requestTimestamp;
    private final Instant responseTimestamp;

    public CorrelatedEvent(RawEvent request, RawEvent response, long latencyMs, String outcome) {
        this(request, response, latencyMs, outcome, Collections.emptyMap(), null, null);
    }

    public CorrelatedEvent(RawEvent request, RawEvent response, long latencyMs, String outcome,
                           Map<String, String> dimensions) {
        this(request, response, latencyMs, outcome, dimensions, null, null);
    }

    public CorrelatedEvent(RawEvent request, RawEvent response, long latencyMs, String outcome,
                           Map<String, String> dimensions, Instant requestTimestamp, Instant responseTimestamp) {
        this.request = request;
        this.response = response;
        this.latencyMs = latencyMs;
        this.outcome = outcome;
        this.dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        this.requestTimestamp = requestTimestamp;
        this.responseTimestamp = responseTimestamp;
    }

    public RawEvent getRequest() { return request; }
    public RawEvent getResponse() { return response; }
    public long getLatencyMs() { return latencyMs; }
    public String getOutcome() { return outcome; }
    public Map<String, String> getDimensions() { return dimensions; }
    public Instant getRequestTimestamp() { return requestTimestamp; }
    public Instant getResponseTimestamp() { return responseTimestamp; }
}