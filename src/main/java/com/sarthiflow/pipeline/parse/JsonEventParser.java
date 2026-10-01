package com.sarthiflow.pipeline.parse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sarthiflow.pipeline.event.RawEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class JsonEventParser implements EventParser {
    @Override
    public Optional<RawEvent> parse(String record) throws EventParseException {
        if (record == null || record.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonElement element = JsonParser.parseString(record);
            if (!element.isJsonObject()) {
                throw new EventParseException("JSON event must be an object");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            flattenObject(element.getAsJsonObject(), "", fields);
            return Optional.of(new RawEvent(fields));
        } catch (EventParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EventParseException("Invalid JSON event", e);
        }
    }

    private void flattenObject(JsonObject object, String prefix, Map<String, String> fields) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonObject()) {
                flattenObject(value.getAsJsonObject(), key, fields);
            } else if (value.isJsonArray()) {
                fields.put(key, value.toString());
            } else {
                fields.put(key, value.getAsString());
            }
        }
    }
}