package com.sarthiflow.model;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class TATTransaction {
    private String correlationKey;
    private ISO8583Message requestMessage;
    private ISO8583Message responseMessage;
    private long tatMillis;
    private boolean isComplete;
    private LocalDateTime createdAt;

    public TATTransaction(String correlationKey, ISO8583Message requestMessage) {
        this.correlationKey = correlationKey;
        this.requestMessage = requestMessage;
        this.createdAt = LocalDateTime.now();
        this.isComplete = false;
    }

    public void setResponseMessage(ISO8583Message responseMessage) {
        this.responseMessage = responseMessage;
        calculateTAT();
        this.isComplete = true;
    }

    private void calculateTAT() {
        if (requestMessage != null && responseMessage != null) {
            this.tatMillis = ChronoUnit.MILLIS.between(
                    requestMessage.getReceiveTime(),
                    responseMessage.getReceiveTime()
            );
        }
    }

    public String getCorrelationKey() {
        return correlationKey;
    }

    public ISO8583Message getRequestMessage() {
        return requestMessage;
    }

    public ISO8583Message getResponseMessage() {
        return responseMessage;
    }

    public long getTatMillis() {
        return tatMillis;
    }

    public boolean isComplete() {
        return isComplete;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isExpired(long expirationMinutes) {
        LocalDateTime expirationTime = createdAt.plusMinutes(expirationMinutes);
        return LocalDateTime.now().isAfter(expirationTime);
    }

    public String getChannel() {
        return requestMessage != null ? requestMessage.getChannel() : "UNKNOWN";
    }

    public String getPan() {
        return requestMessage != null ? requestMessage.getPan() : "";
    }

    public String getStan() {
        return requestMessage != null ? requestMessage.getStan() : "";
    }

    public String getResponseCode() {
        return responseMessage != null ? responseMessage.getResponseCode() : "PENDING";
    }

    @Override
    public String toString() {
        return "TATTransaction{" +
                "key='" + correlationKey + '\'' +
                ", tatMillis=" + tatMillis +
                ", isComplete=" + isComplete +
                ", channel=" + getChannel() +
                ", responseCode=" + getResponseCode() +
                '}';
    }
}

