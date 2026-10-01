package com.sarthiflow.pipeline.metric;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MetricBucket {
    private final Instant bucketStart;
    private final Map<String, String> dimensions;
    private final long count;
    private final long successCount;
    private final long errorCount;
    private final long minLatencyMs;
    private final long maxLatencyMs;
    private final double avgLatencyMs;
    private final long p95LatencyMs;
    private final long p99LatencyMs;
    private final long bucketSeconds;

    public MetricBucket(Instant bucketStart, Map<String, String> dimensions, long count,
                        long successCount, long errorCount, long minLatencyMs,
                        long maxLatencyMs, double avgLatencyMs, long p95LatencyMs,
                        long p99LatencyMs) {
        this(bucketStart, dimensions, count, successCount, errorCount, minLatencyMs,
            maxLatencyMs, avgLatencyMs, p95LatencyMs, p99LatencyMs, 60L);
        }

        public MetricBucket(Instant bucketStart, Map<String, String> dimensions, long count,
                long successCount, long errorCount, long minLatencyMs,
                long maxLatencyMs, double avgLatencyMs, long p95LatencyMs,
                long p99LatencyMs, long bucketSeconds) {
        this.bucketStart = bucketStart;
        this.dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        this.count = count;
        this.successCount = successCount;
        this.errorCount = errorCount;
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.avgLatencyMs = avgLatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.p99LatencyMs = p99LatencyMs;
        if (bucketSeconds <= 0) {
            throw new IllegalArgumentException("Bucket duration must be positive");
        }
        this.bucketSeconds = bucketSeconds;
    }

    public Instant getBucketStart() { return bucketStart; }
    public Map<String, String> getDimensions() { return dimensions; }
    public long getCount() { return count; }
    public long getSuccessCount() { return successCount; }
    public long getErrorCount() { return errorCount; }
    public long getMinLatencyMs() { return minLatencyMs; }
    public long getMaxLatencyMs() { return maxLatencyMs; }
    public double getAvgLatencyMs() { return avgLatencyMs; }
    public long getP95LatencyMs() { return p95LatencyMs; }
    public long getP99LatencyMs() { return p99LatencyMs; }
    public long getBucketSeconds() { return bucketSeconds; }
    public double getSuccessRate() {
        long classified = successCount + errorCount;
        return classified == 0 ? 0.0d : (double) successCount / classified;
    }
    public double getErrorRate() {
        long classified = successCount + errorCount;
        return classified == 0 ? 0.0d : (double) errorCount / classified;
    }
    public double getThroughputPerSecond() {
        return (double) count / bucketSeconds;
    }
}