package com.icici.payment.iso8583.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.icici.payment.iso8583.model.TATTransaction;
import com.icici.payment.iso8583.store.CorrelationStore;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DynatraceMetricsAPIExporter {
    private static final Logger logger = LoggerFactory.getLogger(DynatraceMetricsAPIExporter.class);

    private final CorrelationStore store;
    private final String metricsEndpoint;
    private final String apiToken;
    private final boolean enabled;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public DynatraceMetricsAPIExporter(CorrelationStore store, String metricsEndpoint, String apiToken, boolean enabled) {
        this.store = store;
        this.metricsEndpoint = metricsEndpoint;
        this.apiToken = apiToken;
        this.enabled = enabled;
        this.httpClient = new OkHttpClient();
        this.gson = new GsonBuilder().create();

        System.out.println("[DEBUG-API] DynatraceMetricsAPIExporter initialized");
        System.out.println("[DEBUG-API] Endpoint: " + metricsEndpoint);
    }

    /**
     * Export metrics using Dynatrace Metrics API (JSON format)
     * This is simpler and more reliable than OTLP
     */
    public void exportMetrics() {
        if (!enabled) {
            logger.debug("Dynatrace Metrics API export is disabled");
            return;
        }

        try {
            List<TATTransaction> transactions = store.getCompletedTransactions();
            System.out.println("[DEBUG-API] exportMetrics called. Found " + transactions.size() + " completed transactions");

            if (transactions.isEmpty()) {
                System.out.println("[DEBUG-API] No transactions to export");
                logger.warn("No completed transactions to export");
                return;
            }

            System.out.println("[DEBUG-API] Exporting " + transactions.size() + " transactions via Dynatrace Metrics API");

            // Build JSON payload
            JsonObject payload = buildMetricsPayload(transactions);
            String jsonPayload = gson.toJson(payload);

            System.out.println("[DEBUG-API] Payload size: " + jsonPayload.length() + " bytes");
            System.out.println("[DEBUG-API] Sending to: " + metricsEndpoint);

            // Send to Dynatrace
            sendMetricsPayload(jsonPayload);

        } catch (Exception e) {
            System.out.println("[DEBUG-API] ❌ Error in exportMetrics: " + e.getMessage());
            logger.error("Failed to export metrics via Dynatrace API", e);
        }
    }

    private JsonObject buildMetricsPayload(List<TATTransaction> transactions) {
        // Group by channel for aggregations
        Map<String, List<TATTransaction>> byChannel = transactions.stream()
                .collect(Collectors.groupingBy(TATTransaction::getChannel));

        long currentTime = System.currentTimeMillis();
        JsonArray seriesArray = new JsonArray();

        // 1. Individual transaction TAT values
        for (TATTransaction txn : transactions) {
            JsonObject series = new JsonObject();
            series.addProperty("timeseriesId", "payment.transaction.tat");

            JsonObject dimensions = new JsonObject();
            dimensions.addProperty("channel", txn.getChannel());
            dimensions.addProperty("response_code", txn.getResponseCode());
            series.add("dimensions", dimensions);

            JsonArray dataPoints = new JsonArray();
            JsonArray point = new JsonArray();
            point.add(currentTime);
            point.add(txn.getTatMillis());
            dataPoints.add(point);
            series.add("dataPoints", dataPoints);

            seriesArray.add(series);
            System.out.println("[DEBUG-API] TAT: " + txn.getTatMillis() + "ms (channel=" + txn.getChannel() + ")");
        }

        // 2. Channel-level aggregations
        for (Map.Entry<String, List<TATTransaction>> entry : byChannel.entrySet()) {
            String channel = entry.getKey();
            List<TATTransaction> channelTxns = entry.getValue();

            double avgTat = channelTxns.stream()
                    .mapToLong(TATTransaction::getTatMillis)
                    .average()
                    .orElse(0);

            long minTat = channelTxns.stream()
                    .mapToLong(TATTransaction::getTatMillis)
                    .min()
                    .orElse(0);

            long maxTat = channelTxns.stream()
                    .mapToLong(TATTransaction::getTatMillis)
                    .max()
                    .orElse(0);

            long countTat = channelTxns.size();

            // Average TAT
            JsonObject avgSeries = new JsonObject();
            avgSeries.addProperty("timeseriesId", "payment.channel.tat.avg");
            JsonObject avgDim = new JsonObject();
            avgDim.addProperty("channel", channel);
            avgSeries.add("dimensions", avgDim);
            JsonArray avgPoints = new JsonArray();
            JsonArray avgPoint = new JsonArray();
            avgPoint.add(currentTime);
            avgPoint.add(Math.round(avgTat));
            avgPoints.add(avgPoint);
            avgSeries.add("dataPoints", avgPoints);
            seriesArray.add(avgSeries);

            // Min TAT
            JsonObject minSeries = new JsonObject();
            minSeries.addProperty("timeseriesId", "payment.channel.tat.min");
            JsonObject minDim = new JsonObject();
            minDim.addProperty("channel", channel);
            minSeries.add("dimensions", minDim);
            JsonArray minPoints = new JsonArray();
            JsonArray minPoint = new JsonArray();
            minPoint.add(currentTime);
            minPoint.add(minTat);
            minPoints.add(minPoint);
            minSeries.add("dataPoints", minPoints);
            seriesArray.add(minSeries);

            // Max TAT
            JsonObject maxSeries = new JsonObject();
            maxSeries.addProperty("timeseriesId", "payment.channel.tat.max");
            JsonObject maxDim = new JsonObject();
            maxDim.addProperty("channel", channel);
            maxSeries.add("dimensions", maxDim);
            JsonArray maxPoints = new JsonArray();
            JsonArray maxPoint = new JsonArray();
            maxPoint.add(currentTime);
            maxPoint.add(maxTat);
            maxPoints.add(maxPoint);
            maxSeries.add("dataPoints", maxPoints);
            seriesArray.add(maxSeries);

            // Count
            JsonObject countSeries = new JsonObject();
            countSeries.addProperty("timeseriesId", "payment.channel.tat.count");
            JsonObject countDim = new JsonObject();
            countDim.addProperty("channel", channel);
            countSeries.add("dimensions", countDim);
            JsonArray countPoints = new JsonArray();
            JsonArray countPoint = new JsonArray();
            countPoint.add(currentTime);
            countPoint.add(countTat);
            countPoints.add(countPoint);
            countSeries.add("dataPoints", countPoints);
            seriesArray.add(countSeries);

            System.out.println("[DEBUG-API] Channel " + channel + ": avg=" + Math.round(avgTat) +
                    "ms, min=" + minTat + "ms, max=" + maxTat + "ms, count=" + countTat);
        }

        // 3. Success rate
        long successCount = transactions.stream()
                .filter(t -> "000".equals(t.getResponseCode()))
                .count();
        double successRate = (double) successCount / transactions.size() * 100;

        JsonObject successSeries = new JsonObject();
        successSeries.addProperty("timeseriesId", "payment.success.rate");
        JsonObject successDim = new JsonObject();
        successSeries.add("dimensions", successDim);
        JsonArray successPoints = new JsonArray();
        JsonArray successPoint = new JsonArray();
        successPoint.add(currentTime);
        successPoint.add(Math.round(successRate * 100) / 100.0);  // Round to 2 decimals
        successPoints.add(successPoint);
        successSeries.add("dataPoints", successPoints);
        seriesArray.add(successSeries);

        System.out.println("[DEBUG-API] Success rate: " + successRate + "%");

        // Build final payload
        JsonObject payload = new JsonObject();
        payload.add("series", seriesArray);

        System.out.println("[DEBUG-API] Building payload with " + seriesArray.size() + " metric series");
        return payload;
    }

    private void sendMetricsPayload(String jsonPayload) throws Exception {
        try {
            RequestBody body = RequestBody.create(jsonPayload, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(metricsEndpoint)
                    .post(body)
                    .addHeader("Authorization", "Api-Token " + apiToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "ISO8583-TAT-Extractor/1.0")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                System.out.println("[DEBUG-API] HTTP Response: " + response.code() + " " + response.message());

                if (response.isSuccessful()) {
                    System.out.println("[DEBUG-API] ✅ Metrics exported successfully!");
                    logger.info("Metrics exported successfully via Dynatrace API. Response: {}", response.code());
                } else {
                    System.out.println("[DEBUG-API] ❌ Export failed. Code: " + response.code());
                    if (response.body() != null) {
                        String respBody = response.body().string();
                        System.out.println("[DEBUG-API] Response body: " + respBody.substring(0, Math.min(200, respBody.length())));
                        logger.error("Failed to export metrics. Response: {} {}", response.code(), respBody);
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("[DEBUG-API] ❌ Exception sending payload: " + e.getMessage());
            logger.error("Failed to send metrics payload", e);
            throw e;
        }
    }

    public void close() {
        try {
            httpClient.dispatcher().executorService().shutdown();
            System.out.println("[DEBUG-API] HTTP client closed");
        } catch (Exception e) {
            logger.error("Error closing HTTP client", e);
        }
    }
}
