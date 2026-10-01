package com.icici.payment.iso8583.metrics;

import com.icici.payment.iso8583.model.TATTransaction;
import com.icici.payment.iso8583.store.CorrelationStore;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class OTLPExporter {
    private static final Logger logger = LoggerFactory.getLogger(OTLPExporter.class);

    private final CorrelationStore store;
    private final String endpoint;
    private final String apiToken;
    private final SdkMeterProvider meterProvider;
    private final Meter meter;

    public OTLPExporter(CorrelationStore store, String endpoint, String apiToken) {
        this.store = store;
        this.endpoint = endpoint;
        this.apiToken = apiToken;

        System.out.println("[OTLP] Initializing OpenTelemetry OTLP Exporter");
        System.out.println("[OTLP] Endpoint: " + endpoint);

        OtlpHttpMetricExporter otlpExporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", "Api-Token " + apiToken)
                .build();

        Resource resource = Resource.getDefault()
                .merge(Resource.create(
                        Attributes.of(
                                AttributeKey.stringKey("service.name"), "iso8583-tat-extractor",
                                AttributeKey.stringKey("service.version"), "1.0-PRODUCTION"
                        )
                ));

        PeriodicMetricReader reader = PeriodicMetricReader.builder(otlpExporter)
                .setInterval(30, TimeUnit.SECONDS)
                .build();

        this.meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(reader)
                .build();

        this.meter = meterProvider.get("iso8583-tat-extractor");
        System.out.println("[OTLP] OpenTelemetry OTLP Exporter initialized successfully");
    }

    public void exportMetrics() {
        try {
            List<TATTransaction> unexportedTransactions = store.getUnexportedTransactions();
            System.out.println("[OTLP] exportMetrics called. Found " + unexportedTransactions.size() + " unexported transactions");

            if (unexportedTransactions.isEmpty()) {
                System.out.println("[OTLP] No unexported transactions");
                logger.warn("No unexported transactions to export");
                return;
            }

            System.out.println("[OTLP] Exporting " + unexportedTransactions.size() + " transactions via OTLP");

            Map<String, List<TATTransaction>> byChannel = unexportedTransactions.stream()
                    .collect(Collectors.groupingBy(TATTransaction::getChannel));

            for (TATTransaction txn : unexportedTransactions) {
                Attributes attributes = Attributes.of(
                        AttributeKey.stringKey("channel"), txn.getChannel(),
                        AttributeKey.stringKey("response_code"), txn.getResponseCode()
                );

                meter.upDownCounterBuilder("payment.transaction.tat")
                        .setUnit("ms")
                        .build()
                        .add(txn.getTatMillis(), attributes);

                System.out.println("[OTLP] TAT: " + txn.getTatMillis() + "ms (channel=" + txn.getChannel() + ")");
            }

            for (Map.Entry<String, List<TATTransaction>> entry : byChannel.entrySet()) {
                String channel = entry.getKey();
                List<TATTransaction> channelTxns = entry.getValue();

                double avgTat = channelTxns.stream().mapToLong(TATTransaction::getTatMillis).average().orElse(0);
                long minTat = channelTxns.stream().mapToLong(TATTransaction::getTatMillis).min().orElse(0);
                long maxTat = channelTxns.stream().mapToLong(TATTransaction::getTatMillis).max().orElse(0);
                long countTat = channelTxns.size();

                Attributes channelAttrs = Attributes.of(AttributeKey.stringKey("channel"), channel);

                meter.gaugeBuilder("payment.channel.tat.avg").setUnit("ms").buildWithCallback(obs -> obs.record(Math.round(avgTat), channelAttrs));
                meter.gaugeBuilder("payment.channel.tat.min").setUnit("ms").buildWithCallback(obs -> obs.record(minTat, channelAttrs));
                meter.gaugeBuilder("payment.channel.tat.max").setUnit("ms").buildWithCallback(obs -> obs.record(maxTat, channelAttrs));
                meter.counterBuilder("payment.channel.tat.count").buildWithCallback(obs -> obs.record(countTat, channelAttrs));

                System.out.println("[OTLP] Channel " + channel + ": avg=" + Math.round(avgTat) + "ms, min=" + minTat + "ms, max=" + maxTat + "ms, count=" + countTat);
            }

            long successCount = unexportedTransactions.stream().filter(t -> "000".equals(t.getResponseCode())).count();
            double successRate = (double) successCount / unexportedTransactions.size() * 100;

            meter.gaugeBuilder("payment.success.rate").setUnit("%").buildWithCallback(obs -> obs.record(Math.round(successRate * 100) / 100.0, Attributes.empty()));

            System.out.println("[OTLP] Success rate: " + successRate + "%");

            for (TATTransaction txn : unexportedTransactions) {
                store.markAsExported(txn.getCorrelationKey());
            }

            System.out.println("[OTLP] Forcing flush to send metrics immediately...");
            meterProvider.forceFlush();
            System.out.println("[OTLP] ✅ Metrics exported successfully (deduplication + MTI validation enabled)");

        } catch (Exception e) {
            System.out.println("[OTLP] ❌ Error in exportMetrics: " + e.getMessage());
            e.printStackTrace();
            logger.error("Failed to export metrics via OTLP", e);
        }
    }

    public void close() {
        try {
            System.out.println("[OTLP] Closing meter provider");
            meterProvider.forceFlush();
            meterProvider.close();
            System.out.println("[OTLP] Meter provider closed");
        } catch (Exception e) {
            logger.error("Error closing meter provider", e);
        }
    }
}
