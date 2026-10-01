package com.sarthiflow.pipeline.parse;

import com.sarthiflow.model.ISO8583Message;
import com.sarthiflow.parser.ISO8583Parser;
import com.sarthiflow.pipeline.event.RawEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class Iso8583EventParser implements EventParser {
    private final ISO8583Parser parser = new ISO8583Parser();

    @Override
    public Optional<RawEvent> parse(String record) throws EventParseException {
        if (record == null || record.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            ISO8583Message message = parser.parseMessage(record);
            if (!message.isValidMTI() || message.getReceiveTime() == null
                    || message.getPan().isEmpty() || message.getStan().isEmpty()) {
                return Optional.empty();
            }

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("correlation_key", hash(message.getPan() + "|" + message.getStan()));
            fields.put("direction", message.isRequest() ? "REQ" : "RESP");
            fields.put("timestamp", message.getReceiveTime().atZone(ZoneOffset.UTC).toInstant().toString());
            fields.put("message_id", message.getMessageId());
            fields.put("channel", message.getChannel());
            fields.put("response_code", message.getResponseCode());
            for (Map.Entry<String, String> field : message.getFields().entrySet()) {
                if (!"002".equals(field.getKey())) {
                    fields.put("field." + field.getKey(), field.getValue());
                }
            }
            return Optional.of(new RawEvent(fields));
        } catch (RuntimeException e) {
            throw new EventParseException("Invalid ISO8583 log record", e);
        }
    }

    private String hash(String value) throws EventParseException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                encoded.append(String.format("%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new EventParseException("SHA-256 is not available", e);
        }
    }
}