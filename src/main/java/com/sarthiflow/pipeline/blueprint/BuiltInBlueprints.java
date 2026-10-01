package com.sarthiflow.pipeline.blueprint;

import java.util.Arrays;
import java.util.Collections;

public final class BuiltInBlueprints {
    private BuiltInBlueprints() {
    }

    public static BlueprintConfig iso8583Tat() {
        return new BlueprintConfig.Builder()
                .name("iso8583-tat")
                .correlationKeyField("correlation_key")
                .requestTypeField("direction")
                .responseTypeField("direction")
                .requestValue("REQ")
                .responseValue("RESP")
                .timestampField("timestamp")
                .dimensionFields(Arrays.asList("channel", "response_code"))
                .responseStatusField("response_code")
                .successValues(Collections.singleton("000"))
                .addMetric(new MetricDefinition("transaction.latency", MetricType.HISTOGRAM,
                    MetricSource.LATENCY_MS))
                .addMetric(new MetricDefinition("transaction.count", MetricType.COUNTER,
                    MetricSource.COUNT))
                .addMetric(new MetricDefinition("transaction.success_count", MetricType.COUNTER,
                    MetricSource.SUCCESS_COUNT))
                .addMetric(new MetricDefinition("transaction.error_count", MetricType.COUNTER,
                    MetricSource.ERROR_COUNT))
                .addMetric(new MetricDefinition("transaction.latency.p95", MetricType.GAUGE,
                    MetricSource.P95_LATENCY_MS))
                .addMetric(new MetricDefinition("transaction.latency.p99", MetricType.GAUGE,
                    MetricSource.P99_LATENCY_MS))
                .addMetric(new MetricDefinition("transaction.success_rate", MetricType.RATE,
                    MetricSource.SUCCESS_RATE))
                .addMetric(new MetricDefinition("transaction.error_rate", MetricType.RATE,
                    MetricSource.ERROR_RATE))
                .addMetric(new MetricDefinition("transaction.throughput_per_second", MetricType.RATE,
                    MetricSource.THROUGHPUT_PER_SECOND))
                .build();
    }
}