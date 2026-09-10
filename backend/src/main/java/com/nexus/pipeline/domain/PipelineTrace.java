package com.nexus.pipeline.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "pipeline_traces")
@IdClass(PipelineTraceId.class)
public class PipelineTrace {

    @Id
    @Column(name = "trace_id")
    private String traceId;

    @Id
    @Column(name = "start_timestamp")
    private Instant startTimestamp;

    @Column(name = "end_timestamp", nullable = false)
    private Instant endTimestamp;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "device_id")
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false)
    private PipelineStage stage;

    @Transient
    private int stageIndex;

    @Transient
    private PipelineStage previousStage;

    @Transient
    private PipelineStage nextStage;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "status", nullable = false)
    private String status;

    @Transient
    private int payloadSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    protected PipelineTrace() {}

    public PipelineTrace(String traceId, String correlationId, String deviceId, PipelineStage stage,
                         int stageIndex, PipelineStage previousStage, PipelineStage nextStage,
                         long startTimestamp, long endTimestamp, long durationMs, String status,
                         int payloadSize, Map<String, Object> metadata) {
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.deviceId = deviceId;
        this.stage = stage;
        this.stageIndex = stageIndex;
        this.previousStage = previousStage;
        this.nextStage = nextStage;
        this.startTimestamp = Instant.ofEpochMilli(startTimestamp);
        this.endTimestamp = Instant.ofEpochMilli(endTimestamp);
        this.durationMs = durationMs;
        this.status = status;
        this.payloadSize = payloadSize;
        this.metadata = metadata;
    }

    public static PipelineTrace of(String correlationId, String deviceId, PipelineStage stage,
                                   long startTimestamp, String status, Object payload, Map<String, Object> metadata) {
        long endTimestamp = System.currentTimeMillis();
        int payloadSize = payload != null ? payload.toString().length() : 0;
        return new PipelineTrace(
                UUID.randomUUID().toString(),
                correlationId,
                deviceId,
                stage,
                stage.getIndex(),
                stage.getPrevious(),
                stage.getNext(),
                startTimestamp,
                endTimestamp,
                endTimestamp - startTimestamp,
                status,
                payloadSize,
                metadata != null ? metadata : new HashMap<>()
        );
    }

    @PostLoad
    private void onLoad() {
        if (this.stage != null) {
            this.stageIndex = this.stage.getIndex();
            this.previousStage = this.stage.getPrevious();
            this.nextStage = this.stage.getNext();
        }
    }

    public String getTraceId() { return traceId; }
    public String getCorrelationId() { return correlationId; }
    public String getDeviceId() { return deviceId; }
    public PipelineStage getStage() { return stage; }
    public int getStageIndex() { return stageIndex; }
    public PipelineStage getPreviousStage() { return previousStage; }
    public PipelineStage getNextStage() { return nextStage; }
    public long getStartTimestamp() { return startTimestamp != null ? startTimestamp.toEpochMilli() : 0; }
    public long getEndTimestamp() { return endTimestamp != null ? endTimestamp.toEpochMilli() : 0; }
    public long getDurationMs() { return durationMs; }
    public String getStatus() { return status; }
    public int getPayloadSize() { return payloadSize; }
    public Map<String, Object> getMetadata() { return metadata; }

    // Provide methods with names corresponding to the old record accessors
    public String traceId() { return getTraceId(); }
    public String correlationId() { return getCorrelationId(); }
    public String deviceId() { return getDeviceId(); }
    public PipelineStage stage() { return getStage(); }
    public int stageIndex() { return getStageIndex(); }
    public PipelineStage previousStage() { return getPreviousStage(); }
    public PipelineStage nextStage() { return getNextStage(); }
    public long startTimestamp() { return getStartTimestamp(); }
    public long endTimestamp() { return getEndTimestamp(); }
    public long durationMs() { return getDurationMs(); }
    public String status() { return getStatus(); }
    public int payloadSize() { return getPayloadSize(); }
    public Map<String, Object> metadata() { return getMetadata(); }
}
