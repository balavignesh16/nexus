package com.nexus.rule.trigger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.snapshot.CapturedConnectivity;
import com.nexus.twin.domain.DigitalTwin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether the SOURCE that a committed trigger fact describes is still the latest one: the latest accepted observation of its
 * state, the latest reported transition, the current connectivity generation, or (for a chained fact) a still-current root.
 * A fact whose source was superseded must not decide anything: the newer source has its own fact and its own trigger intent, and
 * evaluating the old fact against newer state would create a second, unrelated decision.
 * <p>
 * The same judgement is made at three points, each against state read at that point and nowhere else:
 * <ul>
 * <li>{@link #againstSnapshot}: against the data ONE evaluation snapshot captured (same REPEATABLE READ transaction), before any
 *     rule is evaluated or any match is written. This is the authoritative pre-decision check.</li>
 * <li>{@link #ofPersistedFact} with {@code lock=false}: a cheap pre-filter for the recovery scanner. It is never sufficient alone.</li>
 * <li>{@link #ofPersistedFact} with {@code lock=true}: inside command acceptance, under the device lock, with the source row held
 *     {@code FOR SHARE}. A concurrent newer observation (which UPDATEs that row) waits for the accepting transaction or has already
 *     committed and is seen: either the acceptance linearizes first (history preserved) or it is denied.</li>
 * </ul>
 * Rows are only locked when {@code lock} is true; a read-only snapshot transaction cannot lock and does not need to.
 */
@Component
public class RuleSourceCurrentness {
    private static final int MAX_CHAIN_DEPTH = 8;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RuleSourceCurrentness(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** @return the supersession reason, or empty when the fact's source is still current in the captured snapshot. */
    public Optional<String> againstSnapshot(TriggerObservation fact, Map<UUID, List<DigitalTwin>> twins, Map<UUID, CapturedConnectivity> links) {
        switch (fact.type()) {
            case ACCEPTED_TELEMETRY -> {
                Optional<Instant> recorded = receivedAt(fact.sourceRef());
                Object stateKey = fact.sourceRef().get("stateKey");
                if (fact.deviceId() == null || stateKey == null || recorded.isEmpty()) return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
                for (DigitalTwin twin : twins.getOrDefault(fact.deviceId(), List.of()))
                    if (stateKey.toString().equals(twin.stateKey()))
                        return twin.receivedAt() != null && twin.receivedAt().equals(recorded.get()) ? Optional.empty() : Optional.of("SUPERSEDED_BY_NEWER_OBSERVATION");
                return Optional.of("SOURCE_STATE_NO_LONGER_PRESENT");
            }
            case CONNECTIVITY_CHANGED -> {
                Object generation = fact.sourceRef().get("generation");
                var link = fact.deviceId() == null ? null : links.get(fact.deviceId());
                if (!(generation instanceof Number n)) return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
                if (link == null) return Optional.of("SOURCE_STATE_NO_LONGER_PRESENT");
                return link.generation() == n.longValue() ? Optional.empty() : Optional.of("SUPERSEDED_BY_NEWER_GENERATION");
            }
            case TWIN_STATE_CHANGED, RULE_MATCHED -> {
                // Read inside the snapshot transaction: coherent with the captured rows even though it is a query.
                return check(fact.type(), fact.deviceId(), fact.sourceRef(), fact.parentMatchId(), 0, false);
            }
            default -> { return Optional.empty(); }
        }
    }

    /** Loads the persisted fact and judges its source; {@code lock} holds the deciding source row FOR SHARE until the transaction ends. */
    public Optional<String> ofPersistedFact(UUID factId, boolean lock) {
        var rows = jdbc.queryForList("SELECT trigger_type, device_id, source_ref::text AS source_ref, parent_match_id FROM rule_trigger_facts WHERE fact_id=?", factId);
        if (rows.isEmpty()) return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
        var row = rows.getFirst();
        try {
            Map<String, Object> ref = mapper.readValue(String.valueOf(row.get("source_ref")), new TypeReference<>() {});
            return check(TriggerType.valueOf(String.valueOf(row.get("trigger_type"))), (UUID) row.get("device_id"), ref, (UUID) row.get("parent_match_id"), 0, lock);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
        }
    }

    /** Trigger types without a current-state counterpart return empty here; recovery policy handles them separately. */
    public Optional<String> check(TriggerType type, UUID deviceId, Map<String, Object> sourceRef, UUID parentMatchId, int depth, boolean lock) {
        return switch (type) {
            case ACCEPTED_TELEMETRY -> telemetry(deviceId, sourceRef, lock);
            case TWIN_STATE_CHANGED -> twinTransition(deviceId, sourceRef, lock);
            case CONNECTIVITY_CHANGED -> connectivity(deviceId, sourceRef, lock);
            case RULE_MATCHED -> chained(parentMatchId, depth, lock);
            default -> Optional.empty();
        };
    }

    private static Optional<Instant> receivedAt(Map<String, Object> sourceRef) {
        Object received = sourceRef.get("receivedAt");
        if (received == null || "null".equals(String.valueOf(received))) return Optional.empty();
        try { return Optional.of(Instant.parse(received.toString())); } catch (RuntimeException unreadable) { return Optional.empty(); }
    }

    private Optional<String> telemetry(UUID deviceId, Map<String, Object> sourceRef, boolean lock) {
        Object stateKey = sourceRef.get("stateKey");
        Optional<Instant> recorded = receivedAt(sourceRef);
        if (deviceId == null || stateKey == null || recorded.isEmpty()) return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
        var current = jdbc.queryForList("SELECT received_at FROM digital_twins WHERE device_id=? AND state_key=?" + (lock ? " FOR SHARE" : ""), deviceId, stateKey.toString());
        if (current.isEmpty() || current.getFirst().get("received_at") == null) return Optional.of("SOURCE_STATE_NO_LONGER_PRESENT");
        Instant now = ((Timestamp) current.getFirst().get("received_at")).toInstant();
        return now.equals(recorded.get()) ? Optional.empty() : Optional.of("SUPERSEDED_BY_NEWER_OBSERVATION");
    }

    private Optional<String> twinTransition(UUID deviceId, Map<String, Object> sourceRef, boolean lock) {
        Object id = sourceRef.get("transitionId"), stateKey = sourceRef.get("stateKey");
        if (id == null || stateKey == null || deviceId == null) return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
        // A reported transition is written in the same transaction as the Twin row update, so holding that row serializes against it.
        // Order is by the observation's receipt time, strictly: ties are never decided by the random transition id.
        if (lock) jdbc.queryForList("SELECT 1 FROM digital_twins WHERE device_id=? AND state_key=? FOR SHARE", deviceId, stateKey.toString());
        boolean superseded = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM twin_state_transitions t JOIN twin_state_transitions o
                ON o.device_id=t.device_id AND o.state_key=t.state_key AND o.transition_type=t.transition_type
               AND COALESCE(o.received_at, o.transitioned_at) > COALESCE(t.received_at, t.transitioned_at)
              WHERE t.transition_id=?)""", Boolean.class, UUID.fromString(id.toString())));
        return superseded ? Optional.of("SUPERSEDED_BY_NEWER_TRANSITION") : Optional.empty();
    }

    private Optional<String> connectivity(UUID deviceId, Map<String, Object> sourceRef, boolean lock) {
        Object generation = sourceRef.get("generation");
        if (deviceId == null || !(generation instanceof Number n)) return Optional.of("SOURCE_NOT_RECONSTRUCTABLE");
        var current = jdbc.queryForList("SELECT generation FROM device_connectivity WHERE device_id=?" + (lock ? " FOR SHARE" : ""), deviceId);
        if (current.isEmpty()) return Optional.of("SOURCE_STATE_NO_LONGER_PRESENT");
        return ((Number) current.getFirst().get("generation")).longValue() == n.longValue() ? Optional.empty() : Optional.of("SUPERSEDED_BY_NEWER_GENERATION");
    }

    private Optional<String> chained(UUID parentMatchId, int depth, boolean lock) {
        if (parentMatchId == null || depth >= MAX_CHAIN_DEPTH) return Optional.of("CHAIN_ROOT_NOT_RECONSTRUCTABLE");
        var rows = jdbc.queryForList("""
            SELECT f.trigger_type, f.device_id, f.source_ref::text AS source_ref, f.parent_match_id
            FROM rule_matches m JOIN rule_trigger_facts f ON f.fact_id=m.trigger_fact_id WHERE m.match_id=?""", parentMatchId);
        if (rows.isEmpty()) return Optional.of("CHAIN_ROOT_NOT_RECONSTRUCTABLE");
        var row = rows.getFirst();
        try {
            Map<String, Object> ref = mapper.readValue(String.valueOf(row.get("source_ref")), new TypeReference<>() {});
            return check(TriggerType.valueOf(String.valueOf(row.get("trigger_type"))), (UUID) row.get("device_id"), ref, (UUID) row.get("parent_match_id"), depth + 1, lock)
                .map(reason -> "CHAIN_ROOT_" + reason);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return Optional.of("CHAIN_ROOT_NOT_RECONSTRUCTABLE");
        }
    }
}
