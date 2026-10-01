package com.sarthiflow.pipeline.parse;

public final class EventParseException extends Exception {
    public EventParseException(String message) {
        super(message);
    }

    public EventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}