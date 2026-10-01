package com.sarthiflow.pipeline.otlp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sarthiflow.pipeline.metric.MetricBucket;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OtlpMetricSender {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String endpoint;
    private final String token;
    private final OkHttpClient client;

    public OtlpMetricSender(String endpoint, String token) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("OTLP endpoint is required");
        }
        this.endpoint = endpoint.trim();
        this.token = token;
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
            metrics.add(histogram("sarthiflow.transaction.latency", bucket));
            metrics.add(sum("sarthiflow.transaction.count", bucket, bucket.getCount()));
            metrics.add(sum("sarthiflow.transaction.success_count", bucket, bucket.getSuccessCount()));
            metrics.add(sum("sarthiflow.transaction.error_count", bucket, bucket.getErrorCount()));
            metrics.add(gauge("sarthiflow.transaction.latency.p95", bucket, bucket.getP95LatencyMs()));
            metrics.add(gauge("sarthiflow.transaction.latency.p99", bucket, bucket.getP99LatencyMs()));
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
        sum.addProperty("aggregationTemporality", 2);
        sum.addProperty("isMonotonic", true);
        JsonObject metric = new JsonObject();
        metric.addProperty("name", name);
        metric.add("sum", sum);
        return metric;
    }

    private JsonObject gauge(String name, MetricBucket bucket, long value) {
        JsonArray dataPoints = new JsonArray();
        JsonObject point = point(bucket);
        point.add("asInt", new JsonPrimitive(Long.toString(value)));
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
        point.addProperty("timeUnixNano", bucket.getBucketStart().toEpochMilli() * 1_000_000L);
        JsonArray attributes = new JsonArray();
        for (Map.Entry<String, String> dimension : bucket.getDimensions().entrySet()) {
            attributes.add(attribute(dimension.getKey(), dimension.getValue()));
        }
        point.add("attributes", attributes);
        return point;
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