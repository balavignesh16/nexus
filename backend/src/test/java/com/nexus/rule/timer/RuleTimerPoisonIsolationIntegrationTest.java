package com.nexus.rule.timer;

import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2: a permanently failing timer at the head of the queue records bounded attempts, is quarantined and never consumed, while
 * independent timers behind it keep firing - also in a fresh Spring context.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuleTimerPoisonIsolationIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired com.nexus.rule.persistence.RuleRegistry registry;

    @Override protected boolean isolatePendingTimers() { return false; }

    static Instant t0;
    static UUID poison;
    static Object firstRegistry;

    private UUID oneShot(UUID device, Instant at) {
        return typedRule(device, "valid " + at, 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null,
            new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, at, null, null));
    }

    private Map<String, Object> timer(UUID id) { return sql.queryForMap("SELECT status,attempts,last_error,invalidation_reason,consumed_at FROM rule_timers WHERE timer_id=?", id); }

    @Test @Order(1) @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void aPermanentlyFailingFirstTimerNeverBlocksIndependentTimers() {
        sql.update("UPDATE rules SET lifecycle='PAUSED', enabled=false WHERE lifecycle='ACTIVE'");
        sql.update("UPDATE rule_timers SET status='INVALIDATED', invalidation_reason='TEST_ISOLATION' WHERE status='PENDING'");
        t0 = now;
        UUID hostDevice = device(), otherDevice = device();
        // A SCHEDULE timer whose version has no schedule: reading the definition fails deterministically (a poison row).
        UUID host = typedRule(hostDevice, "poison host", 1, List.of(new TriggerSubscription(TriggerType.MANUAL)), List.of(), List.of(alert()));
        var root = sql.queryForMap("SELECT active_version_id,execution_epoch FROM rules WHERE rule_id=?", host);
        long revision = sql.queryForObject("SELECT revision FROM rule_versions WHERE version_id=?", Long.class, root.get("active_version_id"));
        poison = UUID.randomUUID();
        sql.update("INSERT INTO rule_timers(timer_id,kind,rule_id,rule_version_id,rule_revision,scope_device_id,generation,due_at,created_at,updated_at) VALUES(?,'SCHEDULE',?,?,?,?,?,?,now(),now())",
            poison, host, root.get("active_version_id"), revision, hostDevice, root.get("execution_epoch"), java.sql.Timestamp.from(t0.minusSeconds(3600)));   // due long before the valid ones
        UUID valid = oneShot(otherDevice, t0.plusSeconds(60));
        now = t0.plusSeconds(120);

        assertEquals(1, timers.processDue(10), "the valid timer behind the poison head fires in the same scan");
        assertEquals(1, matches(valid));
        var afterFirst = timer(poison);
        assertEquals("PENDING", afterFirst.get("status"));
        assertEquals(1, afterFirst.get("attempts"));
        assertNull(afterFirst.get("consumed_at"), "a failed timer is never marked consumed");
        assertTrue(afterFirst.get("last_error").toString().contains("NullPointerException"), afterFirst.get("last_error").toString());

        UUID second = oneShot(otherDevice, now.plusSeconds(30));
        for (int attempt = 2; attempt <= 6; attempt++) {
            now = now.plusSeconds(900);                            // beyond the backoff window each time
            timers.processDue(10);
        }
        assertEquals(1, matches(second), "independent timers keep progressing while the poison retries");
        var quarantined = timer(poison);
        assertEquals("QUARANTINED", quarantined.get("status"));
        assertEquals(5, quarantined.get("attempts"), "bounded retries");
        assertEquals("QUARANTINED_AFTER_FAILURES", quarantined.get("invalidation_reason"));
        assertNull(quarantined.get("consumed_at"));
        firstRegistry = registry;
    }

    @Test @Order(2)
    void aFreshContextKeepsTheQuarantineAndStillServesNewTimers() {
        assertNotSame(firstRegistry, registry, "an actual new Spring context");
        assertEquals("QUARANTINED", timer(poison).get("status"));
        UUID fresh = oneShot(device(), now.plusSeconds(10));
        now = now.plusSeconds(60);
        assertEquals(1, timers.processDue(10));
        assertEquals(1, matches(fresh));
        var poisonAfter = timer(poison);
        assertEquals(5, poisonAfter.get("attempts"), "a quarantined timer is never retried");
        assertEquals("QUARANTINED", poisonAfter.get("status"));
    }
}
