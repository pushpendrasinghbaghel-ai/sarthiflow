package com.sarthiflow.pipeline.sender;

import com.sarthiflow.pipeline.config.PipelineConfiguration;
import com.sarthiflow.pipeline.correlation.CorrelatedEvent;
import com.sarthiflow.pipeline.metric.MetricAggregator;
import com.sarthiflow.pipeline.metric.MetricBucket;
import com.sarthiflow.pipeline.otlp.OtlpMetricSender;
import com.sarthiflow.pipeline.store.SqlitePipelineStore;
import com.sarthiflow.pipeline.store.StoredCorrelatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

public final class AggregatorService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AggregatorService.class);

    private final PipelineConfiguration config;
    private final SqlitePipelineStore store;
    private final MetricAggregator aggregator;
    private final OtlpMetricSender sender;
    private volatile boolean running;

    public AggregatorService(PipelineConfiguration config) throws SQLException {
        if (config.getOtlpEndpoint() == null || config.getOtlpEndpoint().trim().isEmpty()) {
            throw new IllegalArgumentException("Sender requires sarthiflow.sender.endpoint");
        }
        this.config = config;
        this.store = new SqlitePipelineStore(config.getDatabasePath());
        this.aggregator = new MetricAggregator(config.getBlueprint());
        this.sender = new OtlpMetricSender(config.getOtlpEndpoint(), config.getOtlpToken());
    }

    public int exportOnce() throws SQLException, IOException {
        List<StoredCorrelatedEvent> storedEvents = store.findUnexportedEvents(
                config.getBlueprintName(), config.getSenderBatchSize());
        if (storedEvents.isEmpty()) {
            return 0;
        }
        List<CorrelatedEvent> events = storedEvents.stream()
                .map(StoredCorrelatedEvent::getEvent)
                .collect(Collectors.toList());
        List<MetricBucket> buckets = aggregator.aggregate(events);
        if (buckets.isEmpty()) {
            return 0;
        }

        sender.send(buckets);
        for (StoredCorrelatedEvent storedEvent : storedEvents) {
            store.markExported(storedEvent.getId());
        }
        logger.info("Exported {} correlated events in {} metric buckets", storedEvents.size(), buckets.size());
        return storedEvents.size();
    }

    public void runContinuously() throws InterruptedException {
        running = true;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                exportOnce();
            } catch (SQLException | IOException e) {
                logger.warn("Metric export failed; events remain available for retry: {}", e.getMessage());
            }
            Thread.sleep(config.getSenderPollIntervalMillis());
        }
    }

    public void stop() {
        running = false;
    }

    @Override
    public void close() throws SQLException {
        stop();
        store.close();
    }
}