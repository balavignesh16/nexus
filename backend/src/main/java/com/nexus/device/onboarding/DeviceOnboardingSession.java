package com.nexus.device.onboarding;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "device_onboarding_sessions")
public class DeviceOnboardingSession {
    @Id private UUID id;
    @Column(name = "device_id", nullable = false, unique = true) private UUID deviceId;
    @Column(name = "template_id", nullable = false) private UUID templateId;
    @Column(name = "request_key", nullable = false, unique = true) private String requestKey;
    @Column(name = "request_hash", nullable = false) private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OnboardingState state;
    @Column(name = "contract_version", nullable = false) private String contractVersion;
    @Column(name = "status_deadline") private Instant statusDeadline;
    @Column(name = "telemetry_deadline") private Instant telemetryDeadline;
    @Column(name = "first_status_at") private Instant firstStatusAt;
    @Column(name = "first_telemetry_at") private Instant firstTelemetryAt;
    @Column(name = "failure_code") private String failureCode;
    @Column(name = "failure_message") private String failureMessage;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Map<String, Object> metadata;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected DeviceOnboardingSession() {}

    public DeviceOnboardingSession(UUID id, UUID deviceId, UUID templateId, String requestKey,
            String requestHash, Instant statusDeadline, Instant now) {
        this.id = id;
        this.deviceId = deviceId;
        this.templateId = templateId;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.state = OnboardingState.WAITING_FOR_STATUS;
        this.contractVersion = "1.0";
        this.statusDeadline = statusDeadline;
        this.metadata = Map.of();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void observedOnline(Instant now, Instant telemetryDeadline) {
        if (state != OnboardingState.WAITING_FOR_STATUS) return;
        state = OnboardingState.WAITING_FOR_TELEMETRY;
        firstStatusAt = now;
        this.telemetryDeadline = telemetryDeadline;
        updatedAt = now;
    }

    public void operational(Instant now) {
        if (state != OnboardingState.WAITING_FOR_TELEMETRY) throw new IllegalStateException("Session is not waiting for telemetry");
        state = OnboardingState.OPERATIONAL;
        firstTelemetryAt = now;
        failureCode = failureMessage = null;
        updatedAt = now;
    }

    public void rejectTelemetry(String code, String message, Instant now) {
        if (state != OnboardingState.WAITING_FOR_TELEMETRY) return;
        state = OnboardingState.TELEMETRY_REJECTED;
        failureCode = code;
        failureMessage = message;
        updatedAt = now;
    }

    public void statusTimeout(Instant now) {
        if (state != OnboardingState.WAITING_FOR_STATUS) return;
        state = OnboardingState.STATUS_TIMEOUT;
        failureCode = "STATUS_TIMEOUT";
        failureMessage = "No valid online status before deadline";
        updatedAt = now;
    }

    public void retry(Instant now, Instant deadline) {
        if (state != OnboardingState.STATUS_TIMEOUT && state != OnboardingState.TELEMETRY_REJECTED)
            throw new IllegalStateException("Session is not retryable");
        if (state == OnboardingState.STATUS_TIMEOUT) {
            state = OnboardingState.WAITING_FOR_STATUS;
            statusDeadline = deadline;
        } else {
            state = OnboardingState.WAITING_FOR_TELEMETRY;
            telemetryDeadline = deadline;
        }
        failureCode = failureMessage = null;
        updatedAt = now;
    }

    public void disable(Instant now) {
        if (state == OnboardingState.OPERATIONAL) throw new IllegalStateException("Operational device cannot be disabled through onboarding");
        state = OnboardingState.DISABLED;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public UUID getTemplateId() { return templateId; }
    public String getRequestKey() { return requestKey; }
    public String getRequestHash() { return requestHash; }
    public OnboardingState getState() { return state; }
    public String getContractVersion() { return contractVersion; }
    public Instant getStatusDeadline() { return statusDeadline; }
    public Instant getTelemetryDeadline() { return telemetryDeadline; }
    public Instant getFirstStatusAt() { return firstStatusAt; }
    public Instant getFirstTelemetryAt() { return firstTelemetryAt; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Map<String, Object> getMetadata() { return metadata; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
