package com.nexus.rule.trigger;

import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.temporal.RecordedObservation;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.TwinStateTransition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Domain-specific entry points used by each source transaction. A fact is only recorded when the caller holds an actual
 * transaction (a fact outside the source commit would not be durable proof) and only when an active version needs it.
 */
@Component
public class RuleSourceFacts {
    private final RuleTriggerFactRecorder recorder;
    private final TwinStateQualityEvaluator quality;
    private final JdbcTemplate jdbc;

    public RuleSourceFacts(RuleTriggerFactRecorder recorder, TwinStateQualityEvaluator quality, JdbcTemplate jdbc) {
        this.recorder = recorder; this.quality = quality; this.jdbc = jdbc;
    }

    private static boolean transactional() { return TransactionSynchronizationManager.isActualTransactionActive(); }

    private boolean wanted(TriggerType type, UUID deviceId) { return transactional() && recorder.needed(type, deviceId); }

    private static String capabilityOf(DigitalTwin twin) {
        if (twin.capabilityKey() != null) return twin.capabilityKey();
        return twin.legacyCapabilityType() == null ? null : twin.legacyCapabilityType().toLowerCase(Locale.ROOT);
    }

    /**
     * ACCEPTED_TELEMETRY: only a validated, newer observation whose Twin mutation was accepted. The fact records the observation's
     * own typed sample and the quality inputs in force at receipt, so temporal evidence never has to guess from later state.
     */
    public void acceptedTelemetry(UUID telemetryId, DigitalTwin current, Instant deviceTime, String correlationId) {
        if (current == null || !wanted(TriggerType.ACCEPTED_TELEMETRY, current.deviceId())) return;
        var attributes = new LinkedHashMap<String, Object>();
        if (capabilityOf(current) != null) attributes.put("capabilityKey", capabilityOf(current));
        attributes.put("stateKey", current.stateKey());
        var rows = jdbc.queryForList("SELECT status FROM device_connectivity WHERE device_id=?", current.deviceId());
        String connectivity = rows.isEmpty() ? "UNKNOWN" : rows.getFirst().get("status").toString();
        var ref = new LinkedHashMap<String, Object>();
        ref.put("telemetryId", telemetryId.toString()); ref.put("stateKey", current.stateKey());
        if (current.bindingId() != null) ref.put("bindingId", current.bindingId().toString());
        ref.put("receivedAt", String.valueOf(current.receivedAt()));
        ref.put("sample", RecordedObservation.describe(current, quality.liveInputs(current, connectivity)));
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.ACCEPTED_TELEMETRY, telemetryId.toString(),
            current.deviceId(), deviceTime, ref, attributes, correlationId));
    }

    /** TWIN_STATE_CHANGED: an actual semantic REPORTED_STATE_CHANGED transition row. */
    public void twinStateChanged(TwinStateTransition transition, DigitalTwin current) {
        if (transition == null || !wanted(TriggerType.TWIN_STATE_CHANGED, transition.getDeviceId())) return;
        var attributes = new LinkedHashMap<String, Object>();
        if (capabilityOf(current) != null) attributes.put("capabilityKey", capabilityOf(current));
        attributes.put("stateKey", transition.getStateKey());
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.TWIN_STATE_CHANGED, transition.getTransitionId().toString(),
            transition.getDeviceId(), transition.getTransitionedAt(),
            Map.of("transitionId", transition.getTransitionId().toString(), "stateKey", transition.getStateKey()), attributes, transition.getCorrelationId()));
    }

    /** VERIFICATION_CHANGED (and its TIMED_OUT form): an actual CONVERGENCE_CHANGED transition row. */
    public void verificationChanged(TwinStateTransition transition) {
        if (transition == null || !transactional()) return;
        if (!recorder.needed(TriggerType.VERIFICATION_CHANGED, transition.getDeviceId())
                && !recorder.needed(TriggerType.VERIFICATION_TIMED_OUT, transition.getDeviceId())) return;
        var ref = new LinkedHashMap<String, Object>();
        ref.put("transitionId", transition.getTransitionId().toString());
        if (transition.getVerificationCommandId() != null) ref.put("commandId", transition.getVerificationCommandId().toString());
        ref.put("stateKey", transition.getStateKey());
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.VERIFICATION_CHANGED, transition.getTransitionId().toString(),
            transition.getDeviceId(), transition.getTransitionedAt(), ref,
            Map.of("status", String.valueOf(transition.getNewVerificationStatus()), "previousStatus", String.valueOf(transition.getPreviousVerificationStatus())),
            transition.getCorrelationId()));
    }

    /** CONNECTIVITY_CHANGED: a committed status change identified by device and persisted generation. */
    public void connectivityChanged(UUID deviceId, long generation, String previous, String status, Instant at) {
        if (!wanted(TriggerType.CONNECTIVITY_CHANGED, deviceId)) return;
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.CONNECTIVITY_CHANGED, deviceId + "|" + generation, deviceId, at,
            Map.of("generation", generation), Map.of("connectivity", status, "previousConnectivity", previous == null ? "UNKNOWN" : previous), null));
    }

    /** COMMAND_PUBLICATION_FAILED: only after the command was durably accepted and its publication recorded as failed. */
    public void publicationFailed(UUID commandId, UUID deviceId, Instant at, String reason) {
        if (!wanted(TriggerType.COMMAND_PUBLICATION_FAILED, deviceId)) return;
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.COMMAND_PUBLICATION_FAILED, commandId.toString(), deviceId, at,
            Map.of("commandId", commandId.toString(), "reason", String.valueOf(reason)), Map.of(), null));
    }

    /** COMMAND_ACK_REJECTED: the first authoritative rejected ACK for a correlated command on its own device. */
    public void ackRejected(UUID commandId, UUID deviceId, Instant backendReceivedAt, String reason) {
        if (!wanted(TriggerType.COMMAND_ACK_REJECTED, deviceId)) return;
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.COMMAND_ACK_REJECTED, commandId.toString(), deviceId, backendReceivedAt,
            Map.of("commandId", commandId.toString(), "reason", String.valueOf(reason)), Map.of(), null));
    }

    /** COMMAND_ACK_TIMED_OUT: observed once, strictly after the stored deadline, with no ACK present. */
    public void ackTimedOut(UUID commandId, UUID deviceId, Instant deadline) {
        if (!wanted(TriggerType.COMMAND_ACK_TIMED_OUT, deviceId)) return;
        recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.COMMAND_ACK_TIMED_OUT, commandId.toString(), deviceId, deadline,
            Map.of("commandId", commandId.toString(), "ackDeadlineAt", deadline.toString()), Map.of(), null));
    }
}
