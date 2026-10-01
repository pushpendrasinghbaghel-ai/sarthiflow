package com.sarthiflow.pipeline.sender;

import com.sarthiflow.pipeline.config.PipelineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public final class AggregatorApp {
    private static final Logger logger = LoggerFactory.getLogger(AggregatorApp.class);

    private AggregatorApp() {
    }

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "sarthiflow.conf";
        PipelineConfiguration config = PipelineConfiguration.loadFile(Paths.get(configPath));
        try (AggregatorService aggregator = new AggregatorService(config)) {
            Runtime.getRuntime().addShutdownHook(new Thread(aggregator::stop));
            logger.info("Starting SarthiFlow Aggregator/Sender in {} mode for blueprint {}",
                    config.getSenderMode(), config.getBlueprintName());
            if ("tail".equals(config.getSenderMode())) {
                aggregator.runContinuously();
            } else {
                aggregator.exportOnce();
            }
        }
    }
}