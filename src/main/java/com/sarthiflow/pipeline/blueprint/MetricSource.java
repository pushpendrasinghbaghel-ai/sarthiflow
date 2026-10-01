package com.sarthiflow.pipeline.blueprint;

public enum MetricSource {
    LATENCY_MS,
    COUNT,
    SUCCESS_COUNT,
    ERROR_COUNT,
    AVG_LATENCY_MS,
    MIN_LATENCY_MS,
    MAX_LATENCY_MS,
    P95_LATENCY_MS,
    P99_LATENCY_MS,
    SUCCESS_RATE,
    ERROR_RATE,
    THROUGHPUT_PER_SECOND
}