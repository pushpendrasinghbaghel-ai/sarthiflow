package com.sarthiflow.pipeline;

import com.sarthiflow.pipeline.config.PipelineConfiguration;
import com.sarthiflow.pipeline.reader.ReaderService;
import com.sarthiflow.pipeline.sender.AggregatorService;
import com.sarthiflow.pipeline.store.SqlitePipelineStore;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ReaderSenderIntegrationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldReadPersistRetrySendOtlpAndAcknowledgeOnlyAfterSuccess() throws Exception {
        File database = new File(temporaryFolder.getRoot(), "end-to-end.db");
        File input = new File(temporaryFolder.getRoot(), "events.jsonl");
        String request = "{\"request_id\":\"txn-9\",\"kind\":\"begin\","
                + "\"occurred_at\":\"2026-10-01T10:00:00Z\",\"operation\":\"checkout\"}\n";
        String response = "{\"request_id\":\"txn-9\",\"kind\":\"finish\","
                + "\"occurred_at\":\"2026-10-01T10:00:00.250Z\",\"operation\":\"checkout\",\"status\":\"ok\"}\n";
        Files.write(input.toPath(), (request + response).getBytes(StandardCharsets.UTF_8));

        HttpServer endpoint = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<>();
        endpoint.createContext("/v1/metrics", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = attempts.incrementAndGet() == 1 ? 503 : 200;
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        endpoint.start();

        try {
            String databasePath = database.getAbsolutePath().replace("\\", "/");
            String inputPath = input.getAbsolutePath().replace("\\", "/");
            String endpointUrl = "http://localhost:" + endpoint.getAddress().getPort() + "/v1/metrics";
            PipelineConfiguration config = PipelineConfiguration.fromConfig(ConfigFactory.parseString(
                    "sarthiflow {\n"
                            + "  database-path = \"" + databasePath + "\"\n"
                            + "  reader {\n"
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
                            + "    metrics = [\n"
                            + "      { name = transaction.latency, type = HISTOGRAM, source = LATENCY_MS }\n"
                            + "      { name = custom.event.count, type = COUNTER, source = COUNT }\n"
                            + "      { name = custom.event.rate, type = RATE, source = THROUGHPUT_PER_SECOND }\n"
                            + "    ]\n"
                            + "  }\n"
                            + "  sender {\n"
                            + "    endpoint = \"" + endpointUrl + "\"\n"
                            + "    mode = batch\n"
                            + "  }\n"
                            + "}\n"));

            try (ReaderService reader = new ReaderService(config)) {
                reader.processOnce();
            }

            try (AggregatorService sender = new AggregatorService(config)) {
                try {
                    sender.exportOnce();
                    fail("A 503 response should keep the events available for retry");
                } catch (java.io.IOException expected) {
                    assertTrue(expected.getMessage().contains("503"));
                }
                assertEquals(1, sender.exportOnce());
                assertEquals(0, sender.exportOnce());
            }
        } finally {
            endpoint.stop(0);
        }

        assertEquals(2, attempts.get());
        assertNotNull(payload.get());
        assertTrue(payload.get().contains("sarthiflow.transaction.latency"));
        assertTrue(payload.get().contains("sarthiflow.custom.event.count"));
        assertTrue(payload.get().contains("sarthiflow.custom.event.rate"));
        assertFalse(payload.get().contains("sarthiflow.transaction.success_rate"));
        assertTrue(payload.get().contains("checkout"));
        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            assertTrue(store.findUnexportedEvents("generic-json", 100).isEmpty());
        }
    }
}