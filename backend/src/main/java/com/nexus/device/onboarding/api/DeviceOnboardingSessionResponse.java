package com.nexus.device.onboarding.api;

import com.nexus.device.onboarding.DeviceOnboardingSession;
import com.nexus.device.onboarding.OnboardingState;
import java.time.Instant;
import java.util.UUID;

public record DeviceOnboardingSessionResponse(UUID id, UUID deviceId, UUID templateId,
        OnboardingState state, String contractVersion, Instant statusDeadline,
        Instant telemetryDeadline, Instant firstStatusAt, Instant firstTelemetryAt,
        String failureCode, String failureMessage, Instant createdAt, Instant updatedAt) {
    public static DeviceOnboardingSessionResponse from(DeviceOnboardingSession session) {
        return new DeviceOnboardingSessionResponse(session.getId(), session.getDeviceId(), session.getTemplateId(),
                session.getState(), session.getContractVersion(), session.getStatusDeadline(),
                session.getTelemetryDeadline(), session.getFirstStatusAt(), session.getFirstTelemetryAt(),
                session.getFailureCode(), session.getFailureMessage(), session.getCreatedAt(), session.getUpdatedAt());
    }
}
