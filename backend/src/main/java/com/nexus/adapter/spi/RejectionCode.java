package com.nexus.adapter.spi;

/**
 * The closed rejection vocabulary (adapter-contract.md section 7). These are also the {@code reason} metric tags, so the set is fixed;
 * a test compares it with the contract text and fails on an undocumented code.
 */
public enum RejectionCode {
    MALFORMED_JSON, INVALID_FIELDS, INVALID_MESSAGE_ID, INVALID_LEGACY_PAYLOAD, TOPIC_DEVICE_MISMATCH, DEVICE_MISMATCH,
    TRANSPORT_MISMATCH, INVALID_CONTRACT_VERSION, CONTRACT_VERSION_CONFLICT, UNSUPPORTED_CONTRACT_MAJOR,
    UNSUPPORTED_REQUIRED_FEATURE, PAYLOAD_TOO_LARGE, UNAUTHENTICATED, FORBIDDEN_DEVICE,
    /** The core's existing permanent outcomes, reported through the intake ports. */
    ONBOARDING_REJECTED, INVALID_TELEMETRY, DUPLICATE_CONFLICT
}
