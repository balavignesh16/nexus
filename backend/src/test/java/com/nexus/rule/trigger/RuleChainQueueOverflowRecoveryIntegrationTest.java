package com.nexus.rule.trigger;

import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/** A rejected chain-queue submission leaves the committed fact PENDING with a reason, and recovery then routes it exactly once. */
class RuleChainQueueOverflowRecoveryIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleFactRecoveryScanner recovery;

    @Test void aChainWhoseRootObservationWasSupersededExpiresInsteadOfContinuing() {
        UUID device = device();
        UUID parent = typedRule(device, "parent2", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(alert()));
        UUID child = typedRule(device, "child2", 1, List.of(new TriggerSubscription(TriggerType.RULE_MATCHED, Map.of("parentRuleId", parent.toString()))), List.of(), List.of(alert()));
        ((ThreadPoolExecutor) ReflectionTestUtils.getField(router, "queue")).shutdownNow();
        ingest(device, 25, now.minusSeconds(5));
        now = now.plusSeconds(10);
        ingest(device, 26, now);                                  // a newer root observation: the first parent match is history, its chain is stale
        var chainFacts = sql.queryForList("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='RULE_MATCHED' AND device_id=? ORDER BY recorded_at, fact_id", UUID.class, device);
        assertEquals(2, chainFacts.size());
        now = now.plusSeconds(40);
        assertEquals(2, recovery.recover(10));
        assertEquals("EXPIRED", sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, chainFacts.get(0)));
        assertTrue(sql.queryForObject("SELECT routing_error FROM rule_trigger_facts WHERE fact_id=?", String.class, chainFacts.get(0)).startsWith("EXPIRED:CHAIN_ROOT_SUPERSEDED_BY_NEWER_OBSERVATION"));
        assertEquals("ROUTED", sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, chainFacts.get(1)));
        assertEquals(1, matches(child), "only the chain rooted in the still-current observation continued");
    }

    @Test void aFullOrStoppedChainQueueDefersTheFactDurablyAndRecoveryCompletesTheChain() {
        UUID device = device();
        UUID parent = typedRule(device, "parent", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(alert()));
        UUID child = typedRule(device, "child", 1, List.of(new TriggerSubscription(TriggerType.RULE_MATCHED, Map.of("parentRuleId", parent.toString()))), List.of(), List.of(alert()));
        // Every submission is rejected from now on (the same RejectedExecutionException a saturated bounded queue raises).
        ((ThreadPoolExecutor) ReflectionTestUtils.getField(router, "queue")).shutdownNow();

        ingest(device, 25, now.minusSeconds(5));
        assertEquals(1, matches(parent));
        UUID chainFact = sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='RULE_MATCHED' AND device_id=?", UUID.class, device);
        assertEquals("PENDING", sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, chainFact));
        assertEquals("CHAIN_QUEUE_FULL", sql.queryForObject("SELECT routing_error FROM rule_trigger_facts WHERE fact_id=?", String.class, chainFact), "the overflow is recorded, not just logged");
        assertEquals(0, matches(child));

        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));
        assertEquals(1, matches(child), "the chain continued from the persisted parent match");
        assertEquals("ROUTED", sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, chainFact));
        assertEquals(0, recovery.recover(10));
        assertEquals(1, matches(child));
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=?", child));
    }
}
