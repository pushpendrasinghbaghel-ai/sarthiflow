package com.sarthiflow.pipeline.reader;

import com.sarthiflow.pipeline.config.PipelineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public final class ReaderApp {
    private static final Logger logger = LoggerFactory.getLogger(ReaderApp.class);

    private ReaderApp() {
    }

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "sarthiflow.conf";
        PipelineConfiguration config = PipelineConfiguration.loadFile(Paths.get(configPath));
        try (ReaderService reader = new ReaderService(config)) {
            Runtime.getRuntime().addShutdownHook(new Thread(reader::stop));
            logger.info("Starting SarthiFlow Reader in {} mode with {} source(s) and {} worker(s)",
                    config.getMode(), config.getInputPaths().size(), config.getWorkerCount());
            if ("tail".equals(config.getMode())) {
                reader.runContinuously();
            } else {
                reader.processOnce();
            }
        }
    }
}