package com.sarthiflow.pipeline.otlp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sarthiflow.pipeline.blueprint.MetricDefinition;
import com.sarthiflow.pipeline.blueprint.MetricSource;
import com.sarthiflow.pipeline.blueprint.MetricType;
import com.sarthiflow.pipeline.metric.MetricBucket;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OtlpMetricSender {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final List<MetricDefinition> DEFAULT_METRICS = Arrays.asList(
            new MetricDefinition("event.latency", MetricType.HISTOGRAM),
            new MetricDefinition("event.count", MetricType.COUNTER),
            new MetricDefinition("event.throughput_per_second", MetricType.RATE));

    private final String endpoint;
    private final String token;
    private final List<MetricDefinition> metrics;
    private final OkHttpClient client;

    public OtlpMetricSender(String endpoint, String token) {
        this(endpoint, token, DEFAULT_METRICS);
    }

    public OtlpMetricSender(String endpoint, String token, List<MetricDefinition> metrics) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("OTLP endpoint is required");
        }
        this.endpoint = endpoint.trim();
        this.token = token;
        this.metrics = metrics == null || metrics.isEmpty()
                ? DEFAULT_METRICS : Collections.unmodifiableList(new ArrayList<>(metrics));
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void send(List<MetricBucket> buckets) throws IOException {
        if (buckets == null || buckets.isEmpty()) {
            return;
        }

        JsonArray metrics = new JsonArray();
        for (MetricBucket bucket : buckets) {
            for (MetricDefinition definition : this.metrics) {
                String name = metricName(definition.getName());
                MetricSource source = definition.getSource();
                switch (definition.getType()) {
                    case HISTOGRAM:
                        metrics.add(histogram(name, bucket));
                        break;
                    case COUNTER:
                        metrics.add(sum(name, bucket, counterValue(bucket, source)));
                        break;
                    case RATE:
                        metrics.add(doubleGauge(name, bucket, doubleValue(bucket, source)));
                        break;
                    case GAUGE:
                        metrics.add(doubleGauge(name, bucket, doubleValue(bucket, source)));
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported metric type: " + definition.getType());
                }
                    }
        }

        JsonObject scopeMetrics = new JsonObject();
        JsonObject scope = new JsonObject();
        scope.addProperty("name", "sarthiflow");
        scopeMetrics.add("scope", scope);
        scopeMetrics.add("metrics", metrics);

        JsonObject resourceMetrics = new JsonObject();
        JsonArray attributes = new JsonArray();
        attributes.add(attribute("service.name", "sarthiflow"));
        resourceMetrics.add("resource", objectWithArray("attributes", attributes));
        JsonArray scopes = new JsonArray();
        scopes.add(scopeMetrics);
        resourceMetrics.add("scopeMetrics", scopes);

        JsonObject payload = new JsonObject();
        JsonArray resources = new JsonArray();
        resources.add(resourceMetrics);
        payload.add("resourceMetrics", resources);

        Request.Builder request = new Request.Builder()
                .url(endpoint)
            .post(RequestBody.create(payload.toString(), JSON));
        if (token != null && !token.trim().isEmpty()) {
            request.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OTLP endpoint returned HTTP " + response.code());
            }
        }
    }

    private JsonObject histogram(String name, MetricBucket bucket) {
        JsonObject point = point(bucket);
        point.add("count", new JsonPrimitive(Long.toString(bucket.getCount())));
        point.addProperty("sum", bucket.getAvgLatencyMs() * bucket.getCount());
        JsonArray bucketCounts = new JsonArray();
        bucketCounts.add(new JsonPrimitive(Long.toString(bucket.getCount())));
        point.add("bucketCounts", bucketCounts);
        point.add("explicitBounds", new JsonArray());
        JsonArray dataPoints = new JsonArray();
        dataPoints.add(point);
        JsonObject histogram = new JsonObject();
        histogram.add("dataPoints", dataPoints);
        histogram.addProperty("aggregationTemporality", 1);
        JsonObject metric = new JsonObject();
        metric.addProperty("name", name);
        metric.add("histogram", histogram);
        return metric;
    }

    private JsonObject sum(String name, MetricBucket bucket, long value) {
        JsonArray dataPoints = new JsonArray();
        JsonObject point = point(bucket);
        point.add("asInt", new JsonPrimitive(Long.toString(value)));
        dataPoints.add(point);
        JsonObject sum = new JsonObject();
        sum.add("dataPoints", dataPoints);
        sum.addProperty("aggregationTemporality", 1);
        sum.addProperty("isMonotonic", true);
        JsonObject metric = new JsonObject();
        metric.addProperty("name", name);
        metric.add("sum", sum);
        return metric;
    }

    private JsonObject doubleGauge(String name, MetricBucket bucket, double value) {
        JsonArray dataPoints = new JsonArray();
        JsonObject point = point(bucket);
        point.addProperty("asDouble", value);
        dataPoints.add(point);
        JsonObject gauge = new JsonObject();
        gauge.add("dataPoints", dataPoints);
        JsonObject metric = new JsonObject();
        metric.addProperty("name", name);
        metric.add("gauge", gauge);
        return metric;
    }

    private JsonObject point(MetricBucket bucket) {
        JsonObject point = new JsonObject();
        long startTimeUnixNano = toUnixNano(bucket.getBucketStart());
        long endTimeUnixNano = Math.addExact(startTimeUnixNano,
                Math.multiplyExact(bucket.getBucketSeconds(), 1_000_000_000L));
        point.add("startTimeUnixNano", new JsonPrimitive(Long.toString(startTimeUnixNano)));
        point.add("timeUnixNano", new JsonPrimitive(Long.toString(endTimeUnixNano)));
        JsonArray attributes = new JsonArray();
        for (Map.Entry<String, String> dimension : bucket.getDimensions().entrySet()) {
            attributes.add(attribute(dimension.getKey(), dimension.getValue()));
        }
        point.add("attributes", attributes);
        return point;
    }

    private long toUnixNano(java.time.Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private long counterValue(MetricBucket bucket, MetricSource source) {
        switch (source) {
            case COUNT: return bucket.getCount();
            case SUCCESS_COUNT: return bucket.getSuccessCount();
            case ERROR_COUNT: return bucket.getErrorCount();
            default: throw new IllegalArgumentException("Unsupported counter source: " + source);
        }
    }

    private double doubleValue(MetricBucket bucket, MetricSource source) {
        switch (source) {
            case LATENCY_MS: return bucket.getAvgLatencyMs();
            case AVG_LATENCY_MS: return bucket.getAvgLatencyMs();
            case MIN_LATENCY_MS: return bucket.getMinLatencyMs();
            case MAX_LATENCY_MS: return bucket.getMaxLatencyMs();
            case P95_LATENCY_MS: return bucket.getP95LatencyMs();
            case P99_LATENCY_MS: return bucket.getP99LatencyMs();
            case SUCCESS_RATE: return bucket.getSuccessRate();
            case ERROR_RATE: return bucket.getErrorRate();
            case THROUGHPUT_PER_SECOND: return bucket.getThroughputPerSecond();
            default: throw new IllegalArgumentException("Unsupported gauge or rate source: " + source);
        }
    }

    private String metricName(String name) {
        return name.startsWith("sarthiflow.") ? name : "sarthiflow." + name;
    }

    private JsonObject attribute(String key, String value) {
        JsonObject attribute = new JsonObject();
        attribute.addProperty("key", key);
        JsonObject stringValue = new JsonObject();
        stringValue.addProperty("stringValue", value);
        attribute.add("value", stringValue);
        return attribute;
    }

    private JsonObject objectWithArray(String name, JsonArray values) {
        JsonObject object = new JsonObject();
        object.add(name, values);
        return object;
    }
}