package com.sarthiflow.pipeline.metric;

import com.sarthiflow.pipeline.blueprint.BlueprintConfig;
import com.sarthiflow.pipeline.correlation.CorrelatedEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetricAggregator {
    private static final Pattern GRANULARITY = Pattern.compile("(\\d+)(s|m|h|d)");

    private final BlueprintConfig config;
    private final long bucketSeconds;

    public MetricAggregator(BlueprintConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Blueprint configuration is required");
        }
        this.config = config;
        this.bucketSeconds = parseGranularity(config.getGranularity());
    }

    public List<MetricBucket> aggregate(List<CorrelatedEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }

        Map<BucketKey, MutableBucket> buckets = new LinkedHashMap<>();
        for (CorrelatedEvent event : events) {
            if (event == null || event.getLatencyMs() < 0) {
                continue;
            }
            Instant timestamp = eventTimestamp(event);
            if (timestamp == null) {
                continue;
            }
            Instant start = Instant.ofEpochSecond(timestamp.getEpochSecond() / bucketSeconds * bucketSeconds);
            Map<String, String> dimensions = dimensionsFor(event);
            BucketKey key = new BucketKey(start, dimensions);
            buckets.computeIfAbsent(key, ignored -> new MutableBucket(start, dimensions, bucketSeconds)).add(event);
        }

        List<MetricBucket> result = new ArrayList<>();
        buckets.values().forEach(bucket -> result.add(bucket.toMetricBucket()));
        return result;
    }

    private Instant eventTimestamp(CorrelatedEvent event) {
        if (event.getResponseTimestamp() != null) {
            return event.getResponseTimestamp();
        }
        String timestamp = event.getResponse().get(config.getTimestampField());
        if (timestamp == null) {
            timestamp = event.getRequest().get(config.getTimestampField());
        }
        try {
            return timestamp == null ? null : Instant.parse(timestamp);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> dimensionsFor(CorrelatedEvent event) {
        if (!event.getDimensions().isEmpty()) {
            return event.getDimensions();
        }
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (String field : config.getDimensionFields()) {
            String value = event.getResponse().get(field);
            if (value == null) {
                value = event.getRequest().get(field);
            }
            if (value != null) {
                dimensions.put(field, value);
            }
        }
        return dimensions;
    }

    private long parseGranularity(String granularity) {
        Matcher matcher = GRANULARITY.matcher(granularity);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Granularity must use a positive s, m, h, or d interval");
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new IllegalArgumentException("Granularity must be positive");
        }
        switch (matcher.group(2)) {
            case "s": return amount;
            case "m": return Math.multiplyExact(amount, 60L);
            case "h": return Math.multiplyExact(amount, 3600L);
            case "d": return Math.multiplyExact(amount, 86400L);
            default: throw new IllegalArgumentException("Unsupported granularity unit");
        }
    }

    private static final class BucketKey {
        private final Instant start;
        private final Map<String, String> dimensions;

        private BucketKey(Instant start, Map<String, String> dimensions) {
            this.start = start;
            this.dimensions = new TreeMap<>(dimensions);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BucketKey)) return false;
            BucketKey that = (BucketKey) other;
            return start.equals(that.start) && dimensions.equals(that.dimensions);
        }

        @Override
        public int hashCode() {
            return 31 * start.hashCode() + dimensions.hashCode();
        }
    }

    private static final class MutableBucket {
        private final Instant start;
        private final Map<String, String> dimensions;
        private final long bucketSeconds;
        private final List<Long> latencies = new ArrayList<>();
        private long successCount;
        private long errorCount;
        private long sum;

        private MutableBucket(Instant start, Map<String, String> dimensions, long bucketSeconds) {
            this.start = start;
            this.dimensions = new LinkedHashMap<>(dimensions);
            this.bucketSeconds = bucketSeconds;
        }

        private void add(CorrelatedEvent event) {
            long latency = event.getLatencyMs();
            latencies.add(latency);
            sum = Math.addExact(sum, latency);
            if ("SUCCESS".equals(event.getOutcome())) {
                successCount++;
            } else if ("ERROR".equals(event.getOutcome())) {
                errorCount++;
            }
        }

        private MetricBucket toMetricBucket() {
            latencies.sort(Comparator.naturalOrder());
            return new MetricBucket(start, dimensions, latencies.size(), successCount, errorCount,
                    latencies.get(0), latencies.get(latencies.size() - 1),
                    (double) sum / latencies.size(), percentile(0.95), percentile(0.99), bucketSeconds);
        }

        private long percentile(double quantile) {
            int index = (int) Math.ceil(quantile * latencies.size()) - 1;
            return latencies.get(Math.max(0, index));
        }
    }
}