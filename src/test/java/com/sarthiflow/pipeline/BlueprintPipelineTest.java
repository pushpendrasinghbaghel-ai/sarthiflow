package com.sarthiflow.pipeline;

import com.sarthiflow.pipeline.blueprint.BlueprintConfig;
import com.sarthiflow.pipeline.blueprint.MetricDefinition;
import com.sarthiflow.pipeline.blueprint.MetricType;
import com.sarthiflow.pipeline.correlation.CorrelationEngine;
import com.sarthiflow.pipeline.correlation.CorrelatedEvent;
import com.sarthiflow.pipeline.event.RawEvent;
import com.sarthiflow.pipeline.metric.MetricAggregator;
import com.sarthiflow.pipeline.metric.MetricBucket;
import com.sarthiflow.pipeline.otlp.OtlpMetricSender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BlueprintPipelineTest {

    @Test
    public void shouldCorrelateRequestResponsePairsUsingConfiguredKeyAndStatus() {
        BlueprintConfig config = new BlueprintConfig.Builder()
                .name("payment-latency")
                .correlationKeyField("txn_id")
                .requestTypeField("direction")
                .responseTypeField("direction")
                .requestValue("REQ")
                .responseValue("RESP")
                .timestampField("ts")
                .dimensionFields(Arrays.asList("channel", "response_code"))
                .build();

        RawEvent request = new RawEvent(Map.of(
                "txn_id", "T100",
                "direction", "REQ",
                "ts", "2026-10-01T10:00:00.000Z",
                "channel", "ATM",
                "response_code", "000"
        ));

        RawEvent response = new RawEvent(Map.of(
                "txn_id", "T100",
                "direction", "RESP",
                "ts", "2026-10-01T10:00:01.500Z",
                "channel", "ATM",
                "response_code", "000"
        ));

        CorrelationEngine engine = new CorrelationEngine(config);
        List<CorrelatedEvent> correlated = engine.correlate(Arrays.asList(request, response));

        assertEquals(1, correlated.size());
        assertEquals(1500L, correlated.get(0).getLatencyMs());
        assertEquals("ATM", correlated.get(0).getDimensions().get("channel"));
        assertEquals("000", correlated.get(0).getDimensions().get("response_code"));
    }

    @Test
    public void shouldRejectUnmatchedResponsesAndExpiredCorrelations() {
        BlueprintConfig config = new BlueprintConfig.Builder()
                .name("payment-latency")
                .correlationKeyField("txn_id")
                .requestTypeField("direction")
                .responseTypeField("direction")
                .requestValue("REQ")
                .responseValue("RESP")
                .timestampField("ts")
                .build();

        RawEvent request = new RawEvent(Map.of(
                "txn_id", "T200",
                "direction", "REQ",
                "ts", "2026-10-01T10:00:00.000Z"
        ));

        RawEvent orphanResponse = new RawEvent(Map.of(
                "txn_id", "T999",
                "direction", "RESP",
                "ts", "2026-10-01T10:00:00.500Z"
        ));

        RawEvent expiredRequest = new RawEvent(Map.of(
                "txn_id", "T300",
                "direction", "REQ",
                "ts", "2026-09-29T10:00:00.000Z"
        ));

        CorrelationEngine engine = new CorrelationEngine(config);
        List<CorrelatedEvent> correlated = engine.correlate(Arrays.asList(request, orphanResponse, expiredRequest));

        assertEquals(1, correlated.size());
        assertEquals("T200", correlated.get(0).getRequest().get("txn_id"));
        assertFalse(correlated.stream().anyMatch(c -> "T999".equals(c.getResponse().get("txn_id"))));
    }

    @Test
    public void shouldAggregateByConfiguredDimensionsAndGranularity() {
        BlueprintConfig config = new BlueprintConfig.Builder()
                .name("payment-latency")
                .correlationKeyField("txn_id")
                .requestTypeField("direction")
                .responseTypeField("direction")
                .requestValue("REQ")
                .responseValue("RESP")
                .timestampField("ts")
                .dimensionFields(Arrays.asList("channel", "response_code"))
                .granularity("1m")
                .addMetric(new MetricDefinition("latency_ms", MetricType.HISTOGRAM))
                .addMetric(new MetricDefinition("tx_count", MetricType.COUNTER))
                .build();

        CorrelatedEvent first = new CorrelatedEvent(
                new RawEvent(Map.of("txn_id", "A", "direction", "REQ", "ts", "2026-10-01T10:00:10.000Z", "channel", "ATM", "response_code", "000")),
                new RawEvent(Map.of("txn_id", "A", "direction", "RESP", "ts", "2026-10-01T10:00:15.000Z", "channel", "ATM", "response_code", "000")),
                5000L,
                "SUCCESS"
        );

        CorrelatedEvent second = new CorrelatedEvent(
                new RawEvent(Map.of("txn_id", "B", "direction", "REQ", "ts", "2026-10-01T10:00:12.000Z", "channel", "ATM", "response_code", "000")),
                new RawEvent(Map.of("txn_id", "B", "direction", "RESP", "ts", "2026-10-01T10:00:19.000Z", "channel", "ATM", "response_code", "000")),
                7000L,
                "SUCCESS"
        );

        CorrelatedEvent third = new CorrelatedEvent(
                new RawEvent(Map.of("txn_id", "C", "direction", "REQ", "ts", "2026-10-01T10:01:00.000Z", "channel", "POS", "response_code", "500")),
                new RawEvent(Map.of("txn_id", "C", "direction", "RESP", "ts", "2026-10-01T10:01:03.000Z", "channel", "POS", "response_code", "500")),
                3000L,
                "ERROR"
        );

        MetricAggregator aggregator = new MetricAggregator(config);
        List<MetricBucket> buckets = aggregator.aggregate(Arrays.asList(first, second, third));

        assertEquals(3, buckets.size());
        MetricBucket atmBucket = buckets.stream()
                .filter(b -> "ATM".equals(b.getDimensions().get("channel")))
                .findFirst()
                .orElseThrow();

        assertEquals(2, atmBucket.getCount());
        assertEquals(6000.0d, atmBucket.getAvgLatencyMs(), 0.01d);
        assertEquals(5000L, atmBucket.getMinLatencyMs());
        assertEquals(7000L, atmBucket.getMaxLatencyMs());
        assertEquals(2, atmBucket.getSuccessCount());
    }

    @Test
    public void shouldBuildP95MetricForLatencyDistributions() {
        BlueprintConfig config = new BlueprintConfig.Builder()
                .name("payment-latency")
                .correlationKeyField("txn_id")
                .requestTypeField("direction")
                .responseTypeField("direction")
                .requestValue("REQ")
                .responseValue("RESP")
                .timestampField("ts")
                .dimensionFields(Collections.singletonList("channel"))
                .granularity("1m")
                .addMetric(new MetricDefinition("latency_ms", MetricType.HISTOGRAM))
                .build();

        List<CorrelatedEvent> events = Arrays.asList(
                createEvent("T1", "REQ", "2026-10-01T10:00:00.000Z", "ATM", 100L),
                createEvent("T2", "REQ", "2026-10-01T10:00:01.000Z", "ATM", 200L),
                createEvent("T3", "REQ", "2026-10-01T10:00:02.000Z", "ATM", 300L),
                createEvent("T4", "REQ", "2026-10-01T10:00:03.000Z", "ATM", 400L),
                createEvent("T5", "REQ", "2026-10-01T10:00:04.000Z", "ATM", 500L)
        );

        MetricAggregator aggregator = new MetricAggregator(config);
        List<MetricBucket> buckets = aggregator.aggregate(events);

        assertEquals(1, buckets.size());
        assertEquals(500L, buckets.get(0).getP95LatencyMs());
    }

    @Test
    public void shouldSendMetricsToLocalOtlpEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        final boolean[] received = {false};

        server.createContext("/v1/metrics", exchange -> {
            received[0] = true;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            String response = "{\"status\":\"ok\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.start();

        try {
            String endpoint = "http://localhost:" + server.getAddress().getPort() + "/v1/metrics";
            OtlpMetricSender sender = new OtlpMetricSender(endpoint, "test-token");

            MetricBucket bucket = new MetricBucket(
                    Instant.parse("2026-10-01T10:00:00Z"),
                    Collections.singletonMap("channel", "ATM"),
                    3,
                    2,
                    1,
                    100L,
                    900L,
                    550.0d,
                    850L,
                    1000L
            );

            sender.send(Collections.singletonList(bucket));
            assertTrue(received[0]);
        } finally {
            server.stop(0);
        }
    }

    private CorrelatedEvent createEvent(String txnId, String direction, String ts, String channel, long latencyMs) {
        RawEvent request = new RawEvent(Map.of(
                "txn_id", txnId,
                "direction", direction,
                "ts", ts,
                "channel", channel,
                "response_code", "000"
        ));

        RawEvent response = new RawEvent(Map.of(
                "txn_id", txnId,
                "direction", "RESP",
                "ts", Instant.parse(ts).plusMillis(latencyMs).toString(),
                "channel", channel,
                "response_code", "000"
        ));

        return new CorrelatedEvent(request, response, latencyMs, "SUCCESS");
    }
}

