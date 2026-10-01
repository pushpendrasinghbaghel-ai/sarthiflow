package com.sarthiflow.parser;

import com.sarthiflow.model.ISO8583Message;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ISO8583Parser {
    private static final Pattern PID_PATTERN = Pattern.compile("Pid:\\s*(\\d+)");
    private static final Pattern RECEIVED_PATTERN = Pattern.compile("Received At:\\s*(.+)");
    private static final Pattern SENT_PATTERN = Pattern.compile("Sent At:\\s*(.+)");
    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("MessageId:\\s*(\\d+)");
    private static final Pattern FIELD_PATTERN = Pattern.compile("Field\\s+(\\d{3}):\\s*(.*)");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss.SSS");

    public ISO8583Message parseMessage(String messageBlock) {
        ISO8583Message message = new ISO8583Message();
        String[] lines = messageBlock.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Parse PID
            Matcher pidMatcher = PID_PATTERN.matcher(line);
            if (pidMatcher.find()) {
                message.setPid(Integer.parseInt(pidMatcher.group(1)));
            }

            // Parse timestamp and direction
            Matcher receivedMatcher = RECEIVED_PATTERN.matcher(line);
            if (receivedMatcher.find()) {
                message.setDirection("Received");
                message.setReceiveTime(parseTimestamp(receivedMatcher.group(1)));
            }

            Matcher sentMatcher = SENT_PATTERN.matcher(line);
            if (sentMatcher.find()) {
                message.setDirection("Sent");
                message.setReceiveTime(parseTimestamp(sentMatcher.group(1)));
            }

            // Parse MessageId
            Matcher msgIdMatcher = MESSAGE_ID_PATTERN.matcher(line);
            if (msgIdMatcher.find()) {
                message.setMessageId(msgIdMatcher.group(1));
            }

            // Parse fields
            Matcher fieldMatcher = FIELD_PATTERN.matcher(line);
            if (fieldMatcher.find()) {
                String fieldNum = fieldMatcher.group(1);
                String fieldValue = fieldMatcher.group(2);
                message.setField(fieldNum, fieldValue);
            }
        }

        return message;
    }

    private LocalDateTime parseTimestamp(String timestampStr) {
        try {
            return LocalDateTime.parse(timestampStr.trim(), TIMESTAMP_FORMAT);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    public String[] splitMessageBlocks(String logContent) {
        return logContent.split("<=========>");
    }
}

