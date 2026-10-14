package com.nexus.rule.trigger;

import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario 8 (runtime half): a FAILED activation must not touch live temporal or repeat authority. Only a successful switch resets
 * generations; a rejected attempt leaves the running version, its armed temporal state, its pending deadline and its EDGE_TRUE
 * latch exactly as they were.
 */
class RuleFailedActivationRuntimeStateIntegrationTest extends TypedTriggerSupport {
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;

    private Map<String, Object> temporalRow(UUID rule) {
        return sql.queryForMap("SELECT phase,generation,armed_at,due_at,rule_version_id,sequence,updated_at FROM rule_temporal_state WHERE rule_id=?", rule);
    }

    @Test void aRejectedActivationLeavesTemporalTimerAndRepeatAuthorityUntouched() {
        UUID device = device();
        UUID temporal = temporalRule(device, "armed", new TemporalSpec(TemporalOperator.FOR_DURATION, 600L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        ingest(device, 25, now);
        assertEquals("ARMED", phase(temporal));
        assertEquals(1, pendingTimers(temporal));

        UUID edge = typedRule(device, "edge", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(alert()),
            RepeatPolicy.EDGE_TRUE, null);
        now = now.plusSeconds(1);
        ingest(device, 26, now);
        assertEquals(1, count("SELECT count(*) FROM rule_repeat_state WHERE rule_id=? AND latched", edge), "the edge is consumed and latched");

        var activeDefinition = versionStore.version(temporal, versionStore.get(temporal).orElseThrow().activeVersionId());
        var temporalBefore = temporalRow(temporal);
        var repeatBefore = sql.queryForMap("SELECT generation,latched,updated_at FROM rule_repeat_state WHERE rule_id=?", edge);
        var ownerBefore = sql.queryForMap("SELECT lifecycle,active_version_id,execution_epoch,row_revision FROM rules WHERE rule_id=?", temporal);
        var edgeOwnerBefore = sql.queryForMap("SELECT lifecycle,active_version_id,execution_epoch FROM rules WHERE rule_id=?", edge);
        long timerEpochBefore = sql.queryForObject("SELECT generation FROM rule_timers WHERE rule_id=? AND status='PENDING'", Long.class, temporal);
        int factsBefore = count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", device);

        for (UUID id : List.of(temporal, edge)) {
            var current = versionStore.get(id).orElseThrow();
            var draft = lifecycle.draft(new Rule(id, "invalid successor", false, 1, List.of(), List.of(), Map.of(), null), current.rowRevision());
            var failed = lifecycle.transition(id, "activate", draft.rowRevision());
            assertFalse(failed.diagnostics().isEmpty(), "the activation attempt was rejected with diagnostics");
            assertEquals(current.activeVersionId(), failed.activeVersionId(), "the running version stays selected");
            assertEquals(current.executionEpoch(), failed.executionEpoch(), "no new execution epoch");
        }

        assertEquals(temporalBefore, temporalRow(temporal), "armed temporal state is byte-for-byte unchanged (no reset, no new generation)");
        assertEquals(1, pendingTimers(temporal), "the pending deadline survives");
        assertEquals(timerEpochBefore, sql.queryForObject("SELECT generation FROM rule_timers WHERE rule_id=? AND status='PENDING'", Long.class, temporal));
        assertEquals(0, temporalEvents(temporal, "RESET"), "no reset evidence was written");
        assertEquals(repeatBefore, sql.queryForMap("SELECT generation,latched,updated_at FROM rule_repeat_state WHERE rule_id=?", edge), "the EDGE_TRUE latch is unchanged");
        var ownerAfter = sql.queryForMap("SELECT lifecycle,active_version_id,execution_epoch,row_revision FROM rules WHERE rule_id=?", temporal);
        assertEquals(ownerBefore.get("lifecycle"), ownerAfter.get("lifecycle"));
        assertEquals(ownerBefore.get("active_version_id"), ownerAfter.get("active_version_id"));
        assertEquals(ownerBefore.get("execution_epoch"), ownerAfter.get("execution_epoch"));
        var edgeOwnerAfter = sql.queryForMap("SELECT lifecycle,active_version_id,execution_epoch FROM rules WHERE rule_id=?", edge);
        assertEquals(edgeOwnerBefore, edgeOwnerAfter);
        assertEquals(factsBefore, count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", device), "no fact or publication came from the failed attempt");
        assertEquals("ARMED", phase(temporal));

        // Control: a SUCCESSFUL switch does start a fresh generation, so the assertions above are not vacuous.
        var current = versionStore.get(temporal).orElseThrow();
        var good = activeDefinition;
        var draft = lifecycle.draft(new Rule(temporal, "successor", false, 1, good.conditions(), good.actions(), Map.of(), null, null, good.runtime()), current.rowRevision());
        var switched = lifecycle.transition(temporal, "activate", draft.rowRevision());
        assertTrue(switched.diagnostics().isEmpty(), switched.diagnostics().toString());
        assertTrue(((Number) switched.executionEpoch()).longValue() > ((Number) ownerBefore.get("execution_epoch")).longValue(), "a successful activation advances the epoch");
    }
}
