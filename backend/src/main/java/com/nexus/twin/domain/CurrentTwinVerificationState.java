package com.nexus.twin.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;

@Entity @Table(name="current_twin_verification_state")
@IdClass(VerificationStateId.class)
public class CurrentTwinVerificationState {
    @Id @Column(name="device_id") private UUID deviceId;
    @Id @Column(name="target_key") private String targetKey;
    @Id @Column(name="subtarget_key") private String subtargetKey;
    @Column(name="desired_command_id") private UUID desiredCommandId;
    @Enumerated(EnumType.STRING) @Column(name="verification_status") private VerificationStatus verificationStatus;
    @Column(name="verification_updated_at") private Instant verificationUpdatedAt;
    @Column(name="verification_deadline_at") private Instant verificationDeadlineAt;
    @Column(name="mapping_id") private UUID mappingId;
    @Column(name="last_readback_telemetry_id") private UUID lastReadbackTelemetryId;
    @Column(name="last_readback_state_key") private String lastReadbackStateKey;
    @Column(name="last_readback_binding_id") private UUID lastReadbackBindingId;
    @Column(name="last_readback_event_time") private Instant lastReadbackEventTime;
    @Column(name="last_readback_received_at") private Instant lastReadbackReceivedAt;
    @Column(name="last_readback_correlation_id") private String lastReadbackCorrelationId;
    @Column(name="readback_evidence") private String readbackEvidence;
    @Column(name="readback_attribution") private String readbackAttribution;
    @Column(name="comparator_key") private String comparatorKey;
    @Column(name="comparison_result") private String comparisonResult;
    @Column(name="numeric_tolerance") private BigDecimal numericTolerance;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="readback_value",columnDefinition="jsonb") private Map<String,Object> readbackValue;
    @Column(name="discrepancy_reason") private String discrepancyReason;
    @Version private long version;

    protected CurrentTwinVerificationState() {}
    public CurrentTwinVerificationState(UUID deviceId,String targetKey,String subtargetKey,UUID commandId,
            VerificationStatus status,Instant now,Instant deadline,UUID mappingId,String reason) {
        this.deviceId=deviceId;this.targetKey=targetKey;this.subtargetKey=subtargetKey;
        this.desiredCommandId=commandId;this.verificationStatus=status;this.verificationUpdatedAt=now;
        this.verificationDeadlineAt=deadline;this.mappingId=mappingId;this.discrepancyReason=reason;
    }
    public void change(VerificationStatus status,Instant at,String reason) {
        verificationStatus=status; verificationUpdatedAt=at; discrepancyReason=reason;
    }
    public void rearm(UUID commandId,VerificationStatus status,Instant at,Instant deadline,UUID mapping,String reason) {
        desiredCommandId=commandId;verificationStatus=status;verificationUpdatedAt=at;
        verificationDeadlineAt=deadline;mappingId=mapping;discrepancyReason=reason;
        lastReadbackTelemetryId=null;lastReadbackStateKey=null;lastReadbackBindingId=null;
        lastReadbackEventTime=null;lastReadbackReceivedAt=null;lastReadbackCorrelationId=null;
        readbackEvidence=null;readbackAttribution=null;comparatorKey=null;comparisonResult=null;
        numericTolerance=null;readbackValue=null;
    }
    public void observe(UUID telemetryId,String stateKey,UUID bindingId,Instant eventTime,Instant receivedAt,
            String correlationId,Object value,String comparator,BigDecimal tolerance,boolean match) {
        lastReadbackTelemetryId=telemetryId;lastReadbackStateKey=stateKey;lastReadbackBindingId=bindingId;
        lastReadbackEventTime=eventTime;lastReadbackReceivedAt=receivedAt;
        lastReadbackCorrelationId=correlationId;readbackValue=Map.of("value",value);
        readbackEvidence="COMMAND_ECHO";readbackAttribution="COMMAND_ID_MATCH";
        comparatorKey=comparator;numericTolerance=tolerance;comparisonResult=match?"MATCH":"MISMATCH";
        discrepancyReason=match?null:"QUALIFIED_READBACK_MISMATCH";
    }
    public UUID getDeviceId(){return deviceId;} public String getTargetKey(){return targetKey;}
    public String getSubtargetKey(){return subtargetKey;} public UUID getDesiredCommandId(){return desiredCommandId;}
    public VerificationStatus getVerificationStatus(){return verificationStatus;}
    public Instant getVerificationUpdatedAt(){return verificationUpdatedAt;}
    public Instant getVerificationDeadlineAt(){return verificationDeadlineAt;}
    public UUID getMappingId(){return mappingId;} public UUID getLastReadbackTelemetryId(){return lastReadbackTelemetryId;}
    public String getLastReadbackStateKey(){return lastReadbackStateKey;}
    public UUID getLastReadbackBindingId(){return lastReadbackBindingId;}
    public Instant getLastReadbackEventTime(){return lastReadbackEventTime;}
    public Instant getLastReadbackReceivedAt(){return lastReadbackReceivedAt;}
    public String getLastReadbackCorrelationId(){return lastReadbackCorrelationId;}
    public String getReadbackEvidence(){return readbackEvidence;}
    public String getReadbackAttribution(){return readbackAttribution;}
    public String getComparatorKey(){return comparatorKey;}
    public String getComparisonResult(){return comparisonResult;}
    public BigDecimal getNumericTolerance(){return numericTolerance;}
    public Object getReadbackValue(){return readbackValue==null?null:readbackValue.get("value");}
    public String getDiscrepancyReason(){return discrepancyReason;}
}
