package com.sarthiflow.pipeline;

import com.sarthiflow.pipeline.correlation.CorrelatedEvent;
import com.sarthiflow.pipeline.event.RawEvent;
import com.sarthiflow.pipeline.store.SqlitePipelineStore;
import com.sarthiflow.pipeline.store.StoredCorrelatedEvent;
import com.sarthiflow.pipeline.store.StoredRawEvent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SqlitePipelineStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldPersistAndDeduplicateEventsPairsExportsAndCheckpointsAcrossRestart() throws Exception {
        File database = new File(temporaryFolder.getRoot(), "pipeline.db");
        RawEvent request = new RawEvent(Map.of("correlation_key", "key-1", "direction", "REQ",
                "timestamp", "2026-10-01T10:00:00Z", "operation", "read"));
        RawEvent response = new RawEvent(Map.of("correlation_key", "key-1", "direction", "RESP",
                "timestamp", "2026-10-01T10:00:00.250Z", "operation", "read", "status", "ok"));
        long correlatedId;

        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            long requestId = store.saveRawEvent("generic", "key-1", "REQ",
                    Instant.parse("2026-10-01T10:00:00Z"), request);
            assertEquals(requestId, store.saveRawEvent("generic", "key-1", "REQ",
                    Instant.parse("2026-10-01T10:00:00Z"), request));
            long responseId = store.saveRawEvent("generic", "key-1", "RESP",
                    Instant.parse("2026-10-01T10:00:00.250Z"), response);

            CorrelatedEvent pair = new CorrelatedEvent(request, response, 250L, "SUCCESS",
                    Collections.singletonMap("operation", "read"),
                    Instant.parse("2026-10-01T10:00:00Z"),
                    Instant.parse("2026-10-01T10:00:00.250Z"));
            correlatedId = store.saveCorrelatedEvent("generic", "key-1", requestId, responseId, pair);
            assertEquals(correlatedId, store.saveCorrelatedEvent("generic", "key-1", requestId, responseId, pair));
            assertTrue(store.loadUncorrelatedEvents("generic").isEmpty());
            store.saveCheckpoint("/var/log/service.log", 8192L, "inode-42");
        }

        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            List<StoredCorrelatedEvent> pending = store.findUnexportedEvents("generic", 100);
            assertEquals(1, pending.size());
            assertEquals(250L, pending.get(0).getEvent().getLatencyMs());
            assertEquals("SUCCESS", pending.get(0).getEvent().getOutcome());
            assertEquals("ok", pending.get(0).getEvent().getResponse().get("status"));
            assertEquals("read", pending.get(0).getEvent().getDimensions().get("operation"));

            store.markExported(pending.get(0).getId());
            assertTrue(store.findUnexportedEvents("generic", 100).isEmpty());
            assertEquals(8192L, store.getCheckpoint("/var/log/service.log").orElseThrow(AssertionError::new)
                    .getOffsetBytes());
            assertEquals("inode-42", store.getCheckpoint("/var/log/service.log").get().getFileIdentity());
        }
    }

    @Test
    public void shouldRecoverUncorrelatedRawEventsAfterRestart() throws Exception {
        File database = new File(temporaryFolder.getRoot(), "pending.db");
        RawEvent event = new RawEvent(Map.of("correlation_key", "pending-1", "direction", "REQ",
                "timestamp", "2026-10-01T10:00:00Z"));

        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            store.saveRawEvent("generic", "pending-1", "REQ", Instant.parse("2026-10-01T10:00:00Z"), event);
        }

        try (SqlitePipelineStore store = new SqlitePipelineStore(database.getAbsolutePath())) {
            List<StoredRawEvent> pending = store.loadUncorrelatedEvents("generic");
            assertEquals(1, pending.size());
            assertEquals("pending-1", pending.get(0).getCorrelationKey());
            assertEquals("REQ", pending.get(0).getEventType());
            assertEquals("REQ", pending.get(0).getEvent().get("direction"));
        }
    }
}