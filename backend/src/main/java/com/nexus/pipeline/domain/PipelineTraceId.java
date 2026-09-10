package com.nexus.pipeline.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public class PipelineTraceId implements Serializable {
    private String traceId;
    private Instant startTimestamp;

    public PipelineTraceId() {}

    public PipelineTraceId(String traceId, Instant startTimestamp) {
        this.traceId = traceId;
        this.startTimestamp = startTimestamp;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    
    public Instant getStartTimestamp() { return startTimestamp; }
    public void setStartTimestamp(Instant startTimestamp) { this.startTimestamp = startTimestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineTraceId that = (PipelineTraceId) o;
        return Objects.equals(traceId, that.traceId) && Objects.equals(startTimestamp, that.startTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceId, startTimestamp);
    }
}
