package com.sarthiflow.pipeline.parse;

import com.sarthiflow.pipeline.event.RawEvent;

import java.util.Optional;

public interface EventParser {
    Optional<RawEvent> parse(String record) throws EventParseException;
}