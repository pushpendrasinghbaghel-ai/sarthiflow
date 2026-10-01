package com.sarthiflow.pipeline.store;

public final class ReaderCheckpoint {
    private final String sourceId;
    private final long offsetBytes;
    private final String fileIdentity;

    public ReaderCheckpoint(String sourceId, long offsetBytes, String fileIdentity) {
        this.sourceId = sourceId;
        this.offsetBytes = offsetBytes;
        this.fileIdentity = fileIdentity;
    }

    public String getSourceId() { return sourceId; }
    public long getOffsetBytes() { return offsetBytes; }
    public String getFileIdentity() { return fileIdentity; }
}