package com.sarthiflow.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.sarthiflow.model.TATTransaction;
import com.sarthiflow.store.CorrelationStore;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class TATMetricsExporter {
    private static final Logger logger = LoggerFactory.getLogger(TATMetricsExporter.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final CorrelationStore store;
    private final String bindplaneUrl;
    private final OkHttpClient httpClient;

    public TATMetricsExporter(CorrelationStore store, String bindplaneUrl) {
        this.store = store;
        this.bindplaneUrl = bindplaneUrl;
        this.httpClient = new OkHttpClient();
    }

    public void exportMetrics() {
        List<TATTransaction> transactions = store.getCompletedTransactions();
        if (transactions.isEmpty()) {
            logger.warn("No completed transactions to export");
            return;
        }

        Map<String, Object> metricsData = calculateMetrics(transactions);
        exportToBindplane(metricsData);
    }

    private Map<String, Object> calculateMetrics(List<TATTransaction> transactions) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        long timestamp = Instant.now().toEpochMilli();

        // Transaction-level metrics
        JsonArray txnMetrics = new JsonArray();
        for (TATTransaction txn : transactions) {
            JsonObject txnMetric = createTransactionMetric(txn, timestamp);
            txnMetrics.add(txnMetric);
        }

        // Channel-level aggregations
        JsonArray channelMetrics = new JsonArray();
        Map<String, List<TATTransaction>> byChannel = transactions.stream()
                .collect(Collectors.groupingBy(TATTransaction::getChannel));

        for (Map.Entry<String, List<TATTransaction>> entry : byChannel.entrySet()) {
            JsonObject channelMetric = createChannelMetric(entry.getKey(), entry.getValue(), timestamp);
            channelMetrics.add(channelMetric);
        }

        // Response code-level aggregations
        JsonArray responseMetrics = new JsonArray();
        Map<String, List<TATTransaction>> byResponseCode = transactions.stream()
                .collect(Collectors.groupingBy(TATTransaction::getResponseCode));

        for (Map.Entry<String, List<TATTransaction>> entry : byResponseCode.entrySet()) {
            JsonObject responseMetric = createResponseCodeMetric(entry.getKey(), entry.getValue(), timestamp);
            responseMetrics.add(responseMetric);
        }

        metrics.put("transaction_level_metrics", txnMetrics);
        metrics.put("channel_level_metrics", channelMetrics);
        metrics.put("response_code_metrics", responseMetrics);
        metrics.put("summary_stats", createSummaryStats(transactions, timestamp));

        return metrics;
    }

    private JsonObject createTransactionMetric(TATTransaction txn, long timestamp) {
        JsonObject metric = new JsonObject();
        metric.addProperty("metric_name", "payment.transaction.tat");
        metric.addProperty("metric_type", "gauge");
        metric.addProperty("value", txn.getTatMillis());
        metric.addProperty("unit", "milliseconds");

        JsonObject attributes = new JsonObject();
        attributes.addProperty("transaction_id", txn.getStan());
        attributes.addProperty("channel", txn.getChannel());
        attributes.addProperty("response_code", txn.getResponseCode());
        attributes.addProperty("pan_masked", maskPan(txn.getPan()));

        metric.add("attributes", attributes);
        metric.addProperty("timestamp_ms", timestamp);

        return metric;
    }

    private JsonObject createChannelMetric(String channel, List<TATTransaction> transactions, long timestamp) {
        JsonObject metric = new JsonObject();
        metric.addProperty("metric_name", "payment.channel.tat");
        metric.addProperty("metric_type", "gauge");

        DoubleSummaryStatistics stats = transactions.stream()
                .mapToDouble(TATTransaction::getTatMillis)
                .summaryStatistics();

        JsonObject values = new JsonObject();
        values.addProperty("count", stats.getCount());
        values.addProperty("sum_ms", (long) stats.getSum());
        values.addProperty("avg_ms", stats.getAverage());
        values.addProperty("min_ms", stats.getMin());
        values.addProperty("max_ms", stats.getMax());

        metric.add("values", values);
        metric.addProperty("channel", channel);
        metric.addProperty("timestamp_ms", timestamp);

        return metric;
    }

    private JsonObject createResponseCodeMetric(String responseCode, List<TATTransaction> transactions, long timestamp) {
        JsonObject metric = new JsonObject();
        metric.addProperty("metric_name", "payment.response_code.tat");
        metric.addProperty("metric_type", "gauge");

        DoubleSummaryStatistics stats = transactions.stream()
                .mapToDouble(TATTransaction::getTatMillis)
                .summaryStatistics();

        JsonObject values = new JsonObject();
        values.addProperty("count", stats.getCount());
        values.addProperty("avg_ms", stats.getAverage());
        values.addProperty("min_ms", stats.getMin());
        values.addProperty("max_ms", stats.getMax());
        values.addProperty("p95_ms", calculatePercentile(transactions, 95));
        values.addProperty("p99_ms", calculatePercentile(transactions, 99));

        metric.add("values", values);
        metric.addProperty("response_code", responseCode);
        metric.addProperty("timestamp_ms", timestamp);

        return metric;
    }

    private JsonObject createSummaryStats(List<TATTransaction> transactions, long timestamp) {
        JsonObject summary = new JsonObject();
        summary.addProperty("total_transactions", transactions.size());

        DoubleSummaryStatistics stats = transactions.stream()
                .mapToDouble(TATTransaction::getTatMillis)
                .summaryStatistics();

        summary.addProperty("avg_tat_ms", stats.getAverage());
        summary.addProperty("min_tat_ms", stats.getMin());
        summary.addProperty("max_tat_ms", stats.getMax());
        summary.addProperty("p50_ms", calculatePercentile(transactions, 50));
        summary.addProperty("p95_ms", calculatePercentile(transactions, 95));
        summary.addProperty("p99_ms", calculatePercentile(transactions, 99));

        long successCount = transactions.stream()
                .filter(t -> "000".equals(t.getResponseCode()))
                .count();
        summary.addProperty("success_count", successCount);
        summary.addProperty("success_rate_pct", (double) successCount * 100 / transactions.size());

        summary.addProperty("timestamp_ms", timestamp);

        return summary;
    }

    private double calculatePercentile(List<TATTransaction> transactions, double percentile) {
        List<Long> sorted = transactions.stream()
                .map(TATTransaction::getTatMillis)
                .sorted()
                .collect(Collectors.toList());

        int index = (int) ((percentile / 100.0) * sorted.size());
        return index < sorted.size() ? sorted.get(index) : -1;
    }

    private String maskPan(String pan) {
        if (pan.length() <= 4) return "****";
        return "****" + pan.substring(pan.length() - 4);
    }

    private void exportToBindplane(Map<String, Object> metricsData) {
        try {
            String jsonPayload = gson.toJson(metricsData);
            logger.info("Exporting metrics to Bindplane: {}", bindplaneUrl);
            logger.debug("Payload: {}", jsonPayload);

            RequestBody body = RequestBody.create(jsonPayload, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(bindplaneUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.info("Metrics exported successfully. Response: {}", response.code());
                } else {
                    logger.error("Failed to export metrics. Response: {} {}", response.code(), response.body());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to export metrics", e);
        }
    }

    public void close() {
        httpClient.dispatcher().executorService().shutdown();
    }
}

