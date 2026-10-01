package com.icici.payment.iso8583.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class ISO8583Message {
    private static final DateTimeFormatter FIELD_012_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private int pid;
    private LocalDateTime receiveTime;
    private String messageId;
    private String direction; // "Received" or "Sent"
    private Map<String, String> fields;

    public ISO8583Message() {
        this.fields = new HashMap<>();
    }

    public int getPid() {
        return pid;
    }

    public void setPid(int pid) {
        this.pid = pid;
    }

    public LocalDateTime getReceiveTime() {
        return receiveTime;
    }

    public void setReceiveTime(LocalDateTime receiveTime) {
        this.receiveTime = receiveTime;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public void setField(String fieldNum, String value) {
        this.fields.put(fieldNum, value != null ? value.trim() : "");
    }

    public String getField(String fieldNum) {
        return fields.getOrDefault(fieldNum, "");
    }

    // Convenience methods for key fields
    public String getStan() {
        return getField("011");
    }

    public String getPan() {
        return getField("002");
    }

    public String getChannel() {
        return getField("123");
    }

    public String getTransactionRef() {
        return getField("125");
    }

    public String getResponseCode() {
        return getField("039");
    }

    public LocalDateTime getTransactionTime() {
        String field012 = getField("012");
        if (field012 == null || field012.isEmpty()) {
            return receiveTime;
        }
        try {
            String dateStr = getField("017") + field012;
            return LocalDateTime.parse(dateStr, FIELD_012_FORMAT);
        } catch (Exception e) {
            return receiveTime;
        }
    }

    public String getCorrelationKey() {
        return getPan() + "|" + getStan();
    }

    public boolean isRequest() {
        return "1200".equals(messageId);
    }

    public boolean isResponse() {
        return "1210".equals(messageId);
    }

    public boolean isValidMTI() {
        return "1200".equals(messageId) || "1210".equals(messageId);
    }

    @Override
    public String toString() {
        return "ISO8583Message{" +
                "pid=" + pid +
                ", messageId='" + messageId + '\'' +
                ", direction='" + direction + '\'' +
                ", stan=" + getStan() +
                ", pan=" + getPan() +
                ", channel=" + getChannel() +
                '}';
    }
}
