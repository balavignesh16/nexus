package com.nexus.device.onboarding;

public enum OnboardingState {
    WAITING_FOR_STATUS,
    WAITING_FOR_TELEMETRY,
    OPERATIONAL,
    STATUS_TIMEOUT,
    TELEMETRY_REJECTED,
    DISABLED
}
