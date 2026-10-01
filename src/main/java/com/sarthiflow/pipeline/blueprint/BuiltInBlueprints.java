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
                .addMetric(new MetricDefinition("transaction.latency", MetricType.HISTOGRAM))
                .addMetric(new MetricDefinition("transaction.count", MetricType.COUNTER))
                .build();
    }
}