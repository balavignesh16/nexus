package com.nexus.rule.trigger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.TriggerType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a committed trigger fact may still produce rule matches when it is routed by the recovery scanner instead of inline.
 * <p>
 * A fact has three distinct times: when the source occurred (occurredAt), when it was durably recorded (recordedAt) and when it is
 * evaluated (now, never backdated). Evaluation always sees CURRENT state, so recovery is only legitimate where the fact still
 * describes that current state. The contract defines no execution-age threshold, so no clock timeout is invented here; eligibility
 * is decided per trigger type from durable state and fails closed where no such comparison exists:
 * <ul>
 * <li>ACCEPTED_TELEMETRY: eligible only while it is still the latest accepted observation of its state (a newer observation has its
 *     own fact and its own trigger intent);</li>
 * <li>TWIN_STATE_CHANGED: only while no later reported transition exists for the same state;</li>
 * <li>CONNECTIVITY_CHANGED: only while the device is still on that persisted generation;</li>
 * <li>SCHEDULE and TEMPORAL_DUE carry their own durable authority (coalesced slot, generation/due-time/evidence recheck) and stay
 *     eligible; the matcher still rechecks ownership, version and evidence;</li>
 * <li>RULE_MATCHED inherits the eligibility of the fact that produced its parent match, followed up the chain (maximum depth 8):
 *     a chain only continues while its root trigger is still current. A parent from a legacy DomainEvent has no such fact and expires;</li>
 * <li>command outcomes, verification changes and manual requests describe a past event with no current-state counterpart, so a late
 *     evaluation would manufacture new intent from an old event: they expire.</li>
 * </ul>
 * This policy is a pre-filter plus the rules that need no source read (counterpart-less triggers, recorded rule authority). It is NOT the
 * currentness guarantee: that is {@link RuleSourceCurrentness#againstSnapshot} inside the evaluation snapshot and the locked recheck at
 * command acceptance, because a read here can be overtaken by a newer source commit before the snapshot is taken.
 * Temporal evidence is NOT affected: expiry only skips rule matching. The router has already applied the fact's recorded sample to
 * temporal state, so legitimate continuity and resets survive.
 */
@Component
public class RuleRecoveryEligibility {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RuleSourceCurrentness currentness;

    public RuleRecoveryEligibility(JdbcTemplate jdbc, ObjectMapper mapper, RuleSourceCurrentness currentness) { this.jdbc = jdbc; this.mapper = mapper; this.currentness = currentness; }

    /** @return the expiry reason, or empty when the fact may still be evaluated. */
    public Optional<String> expiry(TriggerObservation fact) {
        return judge(fact.type(), fact.deviceId(), fact.sourceRef(), fact.parentMatchId(), 0);
    }

    /** The rule versions/epochs that were active when the fact was recorded; empty when the fact predates the record. */
    public Optional<Map<String, Map<String, Object>>> recordedAuthority(UUID factId) {
        var rows = jdbc.queryForList("SELECT rule_authority::text AS authority FROM rule_trigger_facts WHERE fact_id=?", factId);
        if (rows.isEmpty() || rows.getFirst().get("authority") == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(String.valueOf(rows.getFirst().get("authority")), new TypeReference<Map<String, Map<String, Object>>>() {}));
        } catch (java.io.IOException unreadable) {
            return Optional.empty();
        }
    }

    /** True when exactly this version and epoch of the rule were active when the fact was recorded. */
    public static boolean admits(Map<String, Map<String, Object>> authority, UUID ruleId, UUID versionId, long epoch) {
        var recorded = authority.get(ruleId.toString());
        return recorded != null && versionId.toString().equals(String.valueOf(recorded.get("v")))
            && recorded.get("e") instanceof Number e && e.longValue() == epoch;
    }

    private static final int MAX_CHAIN_DEPTH = 8;

    private Optional<String> judge(TriggerType type, UUID deviceId, Map<String, Object> sourceRef, UUID parentMatchId, int depth) {
        return switch (type) {
            case ACCEPTED_TELEMETRY, TWIN_STATE_CHANGED, CONNECTIVITY_CHANGED -> currentness.check(type, deviceId, sourceRef, null, 0, false);
            case SCHEDULE, TEMPORAL_DUE, LEGACY_DOMAIN_EVENT -> Optional.empty();
            case RULE_MATCHED -> chained(parentMatchId, depth);
            case COMMAND_PUBLICATION_FAILED, COMMAND_ACK_REJECTED, COMMAND_ACK_TIMED_OUT, VERIFICATION_CHANGED, VERIFICATION_TIMED_OUT,
                 MANUAL -> Optional.of("NO_CURRENT_STATE_COUNTERPART");
        };
    }

    private Optional<String> chained(UUID parentMatchId, int depth) {
        if (parentMatchId == null || depth >= MAX_CHAIN_DEPTH) return Optional.of("CHAIN_ROOT_NOT_RECONSTRUCTABLE");
        var rows = jdbc.queryForList("""
            SELECT f.trigger_type, f.device_id, f.source_ref::text AS source_ref, f.parent_match_id
            FROM rule_matches m JOIN rule_trigger_facts f ON f.fact_id=m.trigger_fact_id WHERE m.match_id=?""", parentMatchId);
        if (rows.isEmpty()) return Optional.of("CHAIN_ROOT_NOT_RECONSTRUCTABLE");
        var row = rows.getFirst();
        try {
            Map<String, Object> ref = mapper.readValue(String.valueOf(row.get("source_ref")), new TypeReference<>() {});
            return judge(TriggerType.valueOf(String.valueOf(row.get("trigger_type"))), (UUID) row.get("device_id"), ref, (UUID) row.get("parent_match_id"), depth + 1)
                .map(reason -> "CHAIN_ROOT_" + reason);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return Optional.of("CHAIN_ROOT_NOT_RECONSTRUCTABLE");
        }
    }
}
