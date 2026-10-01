package com.sarthiflow.pipeline;

import com.sarthiflow.pipeline.blueprint.BuiltInBlueprints;
import com.sarthiflow.pipeline.parse.EventParseException;
import com.sarthiflow.pipeline.parse.EventParser;
import com.sarthiflow.pipeline.parse.Iso8583EventParser;
import com.sarthiflow.pipeline.parse.JsonEventParser;
import com.sarthiflow.pipeline.parse.RegexEventParser;
import com.sarthiflow.pipeline.event.RawEvent;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class EventParserTest {
    @Test
    public void shouldParseJsonAndFlattenNestedFields() throws Exception {
        EventParser parser = new JsonEventParser();

        RawEvent event = parser.parse("{\"request_id\":\"r-1\",\"meta\":{\"region\":\"west\"},\"count\":3}")
                .orElseThrow(AssertionError::new);

        assertEquals("r-1", event.get("request_id"));
        assertEquals("west", event.get("meta.region"));
        assertEquals("3", event.get("count"));
    }

    @Test
    public void shouldRejectMalformedJsonAsARecordError() {
        try {
            new JsonEventParser().parse("{invalid json}");
            fail("Expected malformed JSON to be rejected");
        } catch (EventParseException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        }
    }

    @Test
    public void shouldExtractConfiguredRegexGroupsAndIgnoreNonmatchingLines() throws Exception {
        Map<String, Integer> groups = new LinkedHashMap<>();
        groups.put("timestamp", 1);
        groups.put("request_id", 2);
        groups.put("direction", 3);
        EventParser parser = new RegexEventParser(
                Pattern.compile("(\\S+) id=(\\S+) type=(\\S+)"), groups);

        Optional<RawEvent> event = parser.parse("2026-10-01T10:00:00Z id=req-2 type=begin");

        assertTrue(event.isPresent());
        assertEquals("req-2", event.get().get("request_id"));
        assertFalse(parser.parse("unrelated log line").isPresent());
    }

    @Test
    public void shouldNormalizeIso8583AndNeverExposeThePan() throws Exception {
        String block = "Pid: 1 Received At: 10/01/2026 10:00:00.000\n"
                + "MessageId: 1200\n"
                + "Field 002: 4111111111111111\n"
                + "Field 011: 123456\n"
                + "Field 123: ATM\n";

        RawEvent event = new Iso8583EventParser().parse(block).orElseThrow(AssertionError::new);

        assertEquals("REQ", event.get("direction"));
        assertEquals("ATM", event.get("channel"));
        assertEquals("123456", event.get("field.011"));
        assertNotNull(event.get("correlation_key"));
        assertFalse(event.getFields().containsKey("field.002"));
        assertFalse(event.getFields().values().stream().anyMatch(value -> value.contains("4111111111111111")));
        assertEquals("iso8583-tat", BuiltInBlueprints.iso8583Tat().getName());
    }
}