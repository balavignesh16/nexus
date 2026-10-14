package com.nexus.rule.trigger;

import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** H4: bounded, conservative cleanup. Referenced evidence and active runtime state survive; terminal rows go; reruns are no-ops. */
@TestPropertySource(properties = "nexus.rule.retention.batch=2")
class RuleRetentionIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleRetentionService retention;

    private Timestamp ago(long days) { return Timestamp.from(now.minusSeconds(days * 86_400)); }

    private UUID fact(UUID device, String status, long recordedDaysAgo) {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,device_id,occurred_at,recorded_at,routing_status) VALUES(?,'ACCEPTED_TELEMETRY',?,?,?,?,?)",
            id, "k-" + id, device, ago(recordedDaysAgo), ago(recordedDaysAgo), status);
        return id;
    }

    private UUID timer(UUID rule, String kind, String status, long updatedDaysAgo, UUID consumptionFact) {
        var owner = sql.queryForMap("SELECT active_version_id,execution_epoch FROM rules WHERE rule_id=?", rule);
        long revision = sql.queryForObject("SELECT revision FROM rule_versions WHERE version_id=?", Long.class, owner.get("active_version_id"));
        UUID device = sql.queryForObject("SELECT (v.definition->'conditions'->0->>'expectedValue')::uuid FROM rule_versions v WHERE v.version_id=?", UUID.class, owner.get("active_version_id"));
        UUID id = UUID.randomUUID();
        sql.update("""
            INSERT INTO rule_timers(timer_id,kind,rule_id,rule_version_id,rule_revision,condition_id,scope_device_id,generation,due_at,status,consumed_at,consumption_fact_id,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, kind, rule, owner.get("active_version_id"), revision, "TEMPORAL_DUE".equals(kind) ? "c1" : null, device,
            owner.get("execution_epoch"), ago(updatedDaysAgo), status, "CONSUMED".equals(status) ? ago(updatedDaysAgo) : null, consumptionFact, ago(updatedDaysAgo), ago(updatedDaysAgo));
        return id;
    }

    private boolean exists(String table, String column, UUID id) { return count("SELECT count(*) FROM " + table + " WHERE " + column + "=?", id) == 1; }

    @Test void terminalRecordsAreRemovedAndReferencedOrActiveRecordsSurvive() {
        UUID device = device();
        UUID subscribed = typedRule(device, "retention", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(alert()));
        ingest(device, 25, now.minusSeconds(3));                                  // a fact that decided something
        UUID decisive = sql.queryForObject("SELECT trigger_fact_id FROM rule_matches WHERE rule_id=?", UUID.class, subscribed);
        sql.update("UPDATE rule_trigger_facts SET recorded_at=? WHERE fact_id=?", ago(40), decisive);

        UUID expiredOld = fact(device, "EXPIRED", 40), expiredRecent = fact(device, "EXPIRED", 1);
        UUID unreferencedOld = fact(device, "ROUTED", 40), pendingOld = fact(device, "PENDING", 40), failedOld = fact(device, "FAILED", 40);
        UUID quarantinedOld = fact(device, "QUARANTINED", 100), quarantinedRecent = fact(device, "QUARANTINED", 30), routedRecent = fact(device, "ROUTED", 1);
        UUID consumedBy = fact(device, "ROUTED", 40);

        UUID oneShot = typedRule(device, "one-shot memory", 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null,
            new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, now.plusSeconds(3600), null, null));
        UUID interval = typedRule(device, "interval history", 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null,
            new ScheduleSpec(ScheduleSpec.Kind.FIXED_INTERVAL, null, 3600L, null));
        UUID invalidatedOld = timer(interval, "SCHEDULE", "INVALIDATED", 40, null), invalidatedRecent = timer(interval, "SCHEDULE", "INVALIDATED", 1, null);
        UUID quarantinedTimerOld = timer(interval, "SCHEDULE", "QUARANTINED", 70, null), quarantinedTimerRecent = timer(interval, "SCHEDULE", "QUARANTINED", 30, null);
        UUID consumedInterval = timer(interval, "SCHEDULE", "CONSUMED", 40, null);
        UUID consumedOneShot = timer(oneShot, "SCHEDULE", "CONSUMED", 40, null);          // remembers "already fired"
        UUID consumedWithEvidence = timer(interval, "SCHEDULE", "CONSUMED", 1, consumedBy);   // recent, and it references a fact
        UUID pendingKept = sql.queryForObject("SELECT timer_id FROM rule_timers WHERE rule_id=? AND status='PENDING'", UUID.class, interval);

        // Temporal state: the active generation survives; an old generation and its evidence are cleaned.
        UUID temporalDevice = device();
        UUID temporal = temporalRule(temporalDevice, "retained runtime", new TemporalSpec(TemporalOperator.FOR_DURATION, 300L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        ingest(temporalDevice, 25, now.minusSeconds(1));
        assertEquals("ARMED", phase(temporal));
        var owner = sql.queryForMap("SELECT active_version_id,execution_epoch FROM rules WHERE rule_id=?", temporal);
        long revision = sql.queryForObject("SELECT revision FROM rule_versions WHERE version_id=?", Long.class, owner.get("active_version_id"));
        sql.update("INSERT INTO rule_temporal_state(rule_id,rule_version_id,rule_revision,condition_id,scope_device_id,generation,operator,phase,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            temporal, owner.get("active_version_id"), revision, "c1", temporalDevice, ((Number) owner.get("execution_epoch")).longValue() - 1, "FOR_DURATION", "IDLE", ago(40));
        sql.update("INSERT INTO rule_temporal_evidence(rule_id,rule_version_id,condition_id,scope_device_id,generation,event,occurred_at) VALUES(?,?,?,?,?,?,?)",
            temporal, owner.get("active_version_id"), "c1", temporalDevice, 0, "OLD", ago(60));
        long evidenceBefore = count("SELECT count(*) FROM rule_temporal_evidence WHERE rule_id=?", temporal);

        var first = retention.purge();
        assertEquals(3, first.get("facts"), first.toString());           // unreferencedOld + expiredOld + quarantinedOld
        assertEquals(3, first.get("timers"), first.toString());          // invalidatedOld + quarantinedTimerOld + consumedInterval
        assertEquals(1, first.get("temporalState"), first.toString());
        assertEquals(1, first.get("temporalEvidence"), first.toString());

        // Removed
        assertFalse(exists("rule_trigger_facts", "fact_id", unreferencedOld));
        assertFalse(exists("rule_trigger_facts", "fact_id", quarantinedOld));
        assertFalse(exists("rule_trigger_facts", "fact_id", expiredOld));
        assertTrue(exists("rule_trigger_facts", "fact_id", expiredRecent));
        assertFalse(exists("rule_timers", "timer_id", invalidatedOld));
        assertFalse(exists("rule_timers", "timer_id", quarantinedTimerOld));
        assertFalse(exists("rule_timers", "timer_id", consumedInterval));
        assertEquals(0, count("SELECT count(*) FROM rule_temporal_state WHERE rule_id=? AND generation<?", temporal, ((Number) owner.get("execution_epoch")).longValue()));
        assertEquals(evidenceBefore - 1, count("SELECT count(*) FROM rule_temporal_evidence WHERE rule_id=?", temporal));
        // Kept
        assertTrue(exists("rule_trigger_facts", "fact_id", decisive), "a fact that produced a RuleMatch is history");
        assertTrue(exists("rule_trigger_facts", "fact_id", pendingOld), "unrouted work is never purged");
        assertTrue(exists("rule_trigger_facts", "fact_id", failedOld));
        assertTrue(exists("rule_trigger_facts", "fact_id", quarantinedRecent));
        assertTrue(exists("rule_trigger_facts", "fact_id", routedRecent));
        assertTrue(exists("rule_trigger_facts", "fact_id", consumedBy), "referenced by a retained timer's consumption");
        assertTrue(exists("rule_timers", "timer_id", invalidatedRecent));
        assertTrue(exists("rule_timers", "timer_id", quarantinedTimerRecent));
        assertTrue(exists("rule_timers", "timer_id", consumedOneShot), "a consumed one-shot is the memory that stops it re-firing after resume");
        assertTrue(exists("rule_timers", "timer_id", consumedWithEvidence));
        assertTrue(exists("rule_timers", "timer_id", pendingKept), "pending deadlines are never touched");
        assertEquals("ARMED", phase(temporal), "armed runtime state is untouched");
        assertEquals(1, matches(subscribed), "match history is intact");
        assertEquals(1, count("SELECT count(*) FROM rule_action_plans WHERE fact_id=?", decisive));

        var second = retention.purge();
        assertEquals(0, second.values().stream().mapToInt(Integer::intValue).sum(), "repeated cleanup is a no-op: " + second);
    }

    @Test void cleanupIsBoundedPerRunAndDrainsOverRuns() {
        UUID device = device();
        for (int i = 0; i < 60; i++) fact(device, "ROUTED", 40);
        var first = retention.purge();
        assertEquals(40, first.get("facts"), "batch of 2 x at most 20 batches per run");
        assertEquals(20, count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", device));
        var second = retention.purge();
        assertEquals(20, second.get("facts"));
        assertEquals(0, count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", device));
    }
}
