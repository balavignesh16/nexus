package com.nexus.rule.trigger;

import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Measurement, not a pass/fail performance gate: per-sample cost of accepted telemetry with no subscriber, with one subscribed rule
 * and with one temporal rule, against 40 other active rules, plus the cost of one REPEATABLE READ snapshot. Results are written to
 * target/rule-overhead-measurement.txt. It runs on a developer machine against a disposable container, so numbers are indicative
 * only; the assertions check behavior (what is written), never speed.
 */
class RuleRuntimeOverheadMeasurementTest extends TypedTriggerSupport {
    @Autowired RuleEvaluationSnapshotService snapshots;
    private static final int SAMPLES = 40;

    private double millisPerIngest(UUID device) {
        Instant base = now;
        for (int i = 0; i < 3; i++) { now = base.plusSeconds(i); ingest(device, 20 + i, now); }          // warm-up
        long start = System.nanoTime();
        for (int i = 3; i < 3 + SAMPLES; i++) { now = base.plusSeconds(i); ingest(device, 20 + (i % 7), now); }
        return (System.nanoTime() - start) / 1_000_000.0 / SAMPLES;
    }

    @Test void measureTheCostOfFactsRoutingAndSnapshots() throws Exception {
        var report = new StringBuilder();
        UUID bare = device();
        double noRules = millisPerIngest(bare);
        report.append(String.format("ingest, no active rules:                         %.1f ms/sample%n", noRules));

        for (int i = 0; i < 40; i++) {
            UUID other = device();
            typedRule(other, "idle " + i, 1, List.of(new TriggerSubscription(TriggerType.MANUAL)), List.of(), List.of(alert()));
        }
        UUID unsubscribedDevice = device();
        double idle = millisPerIngest(unsubscribedDevice);
        assertEquals(0, count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", unsubscribedDevice), "no active version needs the fact, so none is written");
        report.append(String.format("ingest, 40 active rules, no subscriber:          %.1f ms/sample (0 facts written)%n", idle));

        UUID subscribed = device();
        typedRule(subscribed, "subscribed", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("1000")), List.of(alert()));
        double oneSubscriber = millisPerIngest(subscribed);
        assertEquals(3 + SAMPLES, count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", subscribed));
        assertEquals(3 + SAMPLES, count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=? AND routing_status='ROUTED'", subscribed));
        report.append(String.format("ingest, 40 active rules, 1 subscribed rule:      %.1f ms/sample (%d facts, all ROUTED)%n", oneSubscriber, 3 + SAMPLES));

        UUID temporalDevice = device();
        temporalRule(temporalDevice, "temporal", new TemporalSpec(TemporalOperator.FOR_DURATION, 3600L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        double temporal = millisPerIngest(temporalDevice);
        report.append(String.format("ingest, 40 active rules, 1 FOR_DURATION rule:    %.1f ms/sample%n", temporal));

        var trigger = TriggerObservation.legacy(new com.nexus.event.domain.DomainEvent(UUID.randomUUID(), com.nexus.event.domain.EventType.TWIN_UPDATED, subscribed, now, "m", Map.of(), "INFO", "m"));
        for (int i = 0; i < 3; i++) snapshots.acquire(trigger, now);
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) snapshots.acquire(trigger, now);
        report.append(String.format("one REPEATABLE READ snapshot (41 rules loaded): %.1f ms%n", (System.nanoTime() - start) / 1_000_000.0 / 20));
        report.append("Indicative only: single developer machine, disposable PostgreSQL container, no concurrent load, no connection-pool saturation test.\n");

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "rule-overhead-measurement.txt"), report.toString());
        System.out.println("RULE_OVERHEAD_MEASUREMENT\n" + report);
        assertTrue(report.length() > 0);
    }
}
