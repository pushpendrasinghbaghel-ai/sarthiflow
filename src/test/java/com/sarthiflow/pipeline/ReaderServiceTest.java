package com.sarthiflow.pipeline;

import com.sarthiflow.pipeline.config.PipelineConfiguration;
import com.sarthiflow.pipeline.reader.ReaderService;
import com.sarthiflow.pipeline.store.SqlitePipelineStore;
import com.sarthiflow.pipeline.store.StoredCorrelatedEvent;
import com.typesafe.config.ConfigFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class ReaderServiceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldIngestCorrelateCheckpointAndReplayWithoutDuplicatePairs() throws Exception {
        File database = new File(temporaryFolder.getRoot(), "reader.db");
        File input = new File(temporaryFolder.getRoot(), "events.jsonl");
        String records = "{invalid json}\n"
                + "{\"request_id\":\"txn-7\",\"kind\":\"begin\",\"occurred_at\":\"2026-10-01T10:00:00Z\",\"operation\":\"lookup\"}\n"
                + "{\"request_id\":\"txn-7\",\"kind\":\"finish\",\"occurred_at\":\"2026-10-01T10:00:00.125Z\",\"operation\":\"lookup\",\"status\":\"ok\"}\n";
        Files.write(input.toPath(), records.getBytes(StandardCharsets.UTF_8));
        PipelineConfiguration config = configuration(database, input);

        try (ReaderService reader = new ReaderService(config)) {
            reader.processOnce();
            reader.processOnce();
        }

        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            List<StoredCorrelatedEvent> events = store.findUnexportedEvents("generic-json", 100);
            assertEquals(1, events.size());
            assertEquals(125L, events.get(0).getEvent().getLatencyMs());
            assertEquals("SUCCESS", events.get(0).getEvent().getOutcome());
            assertEquals("lookup", events.get(0).getEvent().getDimensions().get("operation"));
            assertTrue(store.loadUncorrelatedEvents("generic-json").isEmpty());
            assertEquals(records.getBytes(StandardCharsets.UTF_8).length,
                    store.getCheckpoint(input.getAbsolutePath()).orElseThrow(AssertionError::new).getOffsetBytes());
        }
    }

    @Test
    public void shouldResetCheckpointWhenSourceIsRewrittenToTheSameLength() throws Exception {
        File database = new File(temporaryFolder.getRoot(), "rewrite.db");
        File input = new File(temporaryFolder.getRoot(), "rewrite.jsonl");
        String original = "{\"request_id\":\"txn-7\",\"kind\":\"begin\",\"occurred_at\":\"2026-10-01T10:00:00Z\"}\n"
                + "{\"request_id\":\"txn-7\",\"kind\":\"finish\",\"occurred_at\":\"2026-10-01T10:00:00.125Z\"}\n";
        String replacement = original.replace("txn-7", "txn-8");
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length,
                replacement.getBytes(StandardCharsets.UTF_8).length);
        Files.write(input.toPath(), original.getBytes(StandardCharsets.UTF_8));
        PipelineConfiguration config = configuration(database, input);

        try (ReaderService reader = new ReaderService(config)) {
            reader.processOnce();
            Files.write(input.toPath(), replacement.getBytes(StandardCharsets.UTF_8));
            reader.processOnce();
        }

        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            assertEquals(2, store.findUnexportedEvents("generic-json", 100).size());
        }
    }

    private PipelineConfiguration configuration(File database, File input) {
        String databasePath = database.getAbsolutePath().replace("\\", "/");
        String inputPath = input.getAbsolutePath().replace("\\", "/");
        String config = "sarthiflow {\n"
                + "  reader {\n"
                + "    database-path = \"" + databasePath + "\"\n"
                + "    inputs = [\"" + inputPath + "\"]\n"
                + "    format = json\n"
                + "    mode = batch\n"
                + "    workers = 2\n"
                + "  }\n"
                + "  blueprint {\n"
                + "    name = generic-json\n"
                + "    correlation-key-field = request_id\n"
                + "    request-type-field = kind\n"
                + "    response-type-field = kind\n"
                + "    request-value = begin\n"
                + "    response-value = finish\n"
                + "    timestamp-field = occurred_at\n"
                + "    dimensions = [operation]\n"
                + "    response-status-field = status\n"
                + "    success-values = [ok]\n"
                + "    granularity = 1m\n"
                + "  }\n"
                + "}\n";
        return PipelineConfiguration.fromConfig(ConfigFactory.parseString(config));
    }
}