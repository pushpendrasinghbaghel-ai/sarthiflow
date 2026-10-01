package com.sarthiflow.pipeline.parse;

import com.sarthiflow.pipeline.event.RawEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegexEventParser implements EventParser {
    private final Pattern pattern;
    private final Map<String, Integer> captureGroups;

    public RegexEventParser(Pattern pattern, Map<String, Integer> captureGroups) {
        if (pattern == null || captureGroups == null || captureGroups.isEmpty()) {
            throw new IllegalArgumentException("A regex pattern and field capture mapping are required");
        }
        this.pattern = pattern;
        this.captureGroups = new LinkedHashMap<>(captureGroups);
        for (Map.Entry<String, Integer> mapping : this.captureGroups.entrySet()) {
            if (mapping.getKey() == null || mapping.getKey().trim().isEmpty()
                    || mapping.getValue() == null || mapping.getValue() < 1
                    || mapping.getValue() > pattern.matcher("").groupCount()) {
                throw new IllegalArgumentException("Invalid regex capture mapping for field " + mapping.getKey());
            }
        }
    }

    @Override
    public Optional<RawEvent> parse(String record) {
        if (record == null || record.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(record);
        if (!matcher.find()) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        captureGroups.forEach((field, group) -> {
            String value = matcher.group(group);
            if (value != null) {
                fields.put(field, value);
            }
        });
        return Optional.of(new RawEvent(fields));
    }
}