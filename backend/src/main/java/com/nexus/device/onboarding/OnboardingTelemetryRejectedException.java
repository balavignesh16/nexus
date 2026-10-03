package com.nexus.device.onboarding;

/** Permanent device-payload failure: MQTT may ACK/drop after recording it. */
public class OnboardingTelemetryRejectedException extends IllegalArgumentException {
    public OnboardingTelemetryRejectedException(String message) { super(message); }
}
