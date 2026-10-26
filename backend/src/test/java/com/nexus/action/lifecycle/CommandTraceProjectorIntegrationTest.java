package com.nexus.action.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trace evidence for every command lifecycle transition (ledger P4-095, D3), latency metrics from backend timestamps (P4-057, P4-058,
 * P4-100): idempotent, recoverable from history alone, exactly-once across concurrent projectors, never altering the outcome it describes.
 */
class CommandTraceProjectorIntegrationTest extends LifecycleFixture {
    @Autowired CommandTraceProjector projector;
    @Autowired MeterRegistry meters;

    /** Projects everything that is pending (other tests' facts included: the database is shared by this class). */
    private void drainProjector() {
        for (int i = 0; i < 100 && projector.project() > 0; i++) { /* until no fact is missing a trace */ }
    }

    private List<Map<String, Object>> traces(UUID id) {
        return jdbc.queryForList("SELECT trace_id, stage, status, device_id, correlation_id, duration_ms, metadata::text AS metadata FROM pipeline_traces"
                + " WHERE trace_id LIKE ? ORDER BY trace_id", "cmd:" + id + ":%");
    }

    private double timerCount(String stage) {
        var timer = meters.find("nexus.command.latency").tag("stage", stage).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    @Timeout(120)
    void everyLifecycleFactGetsExactlyOneStableTraceAndASecondPassWritesNothing() throws Exception {
        UUID id = sendPublished();
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        List<String> facts = facts(id);
        drainProjector();

        List<Map<String, Object>> rows = traces(id);
        assertEquals(facts.size(), rows.size(), "one trace per fact: " + facts);
        for (int sequence = 1; sequence <= facts.size(); sequence++) {
            final int s = sequence;
            var row = rows.stream().filter(r -> r.get("trace_id").equals("cmd:" + id + ":" + s)).findFirst().orElseThrow();
            assertEquals("ACTION", row.get("stage"));
            assertEquals(facts.get(s - 1), row.get("status"), "the status is the fact type");
            assertEquals(deviceId.toString(), row.get("device_id"));
        }
        assertEquals(0, projector.project(), "a second pass finds every trace present and writes nothing");
        assertEquals(rows, traces(id), "and changes nothing");
    }

    @Test
    @Timeout(120)
    void aLostTraceIsRecoveredFromHistoryAloneWithTheSameIdentityAndContent() throws Exception {
        UUID id = sendPublished();
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        drainProjector();
        List<Map<String, Object>> before = traces(id);
        assertFalse(before.isEmpty());

        // the process died after the facts committed and before their traces did: simulate by removing the traces
        jdbc.update("DELETE FROM pipeline_traces WHERE trace_id LIKE ?", "cmd:" + id + ":%");
        assertEquals(0, traces(id).size());

        drainProjector();
        assertEquals(before, traces(id), "recovered traces are identical to the lost ones (same ids, same content)");
        List<String> factsNow = facts(id);
        assertEquals(before.size(), factsNow.size());
    }

    @Test
    @Timeout(180)
    void concurrentProjectorsWriteEachTraceOnceAndCountEachMetricOnce() throws Exception {
        drainProjector();
        UUID id = sendPublished();
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        int facts = facts(id).size();
        double publishBefore = timerCount("publish"), ackBefore = timerCount("ack");

        int projectors = 6;
        ExecutorService pool = Executors.newFixedThreadPool(projectors);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < projectors; i++) results.add(pool.submit(() -> { go.await(); return projector.project(); }));
        go.countDown();
        int written = 0;
        for (Future<Integer> f : results) written += f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(facts, traces(id).size(), "no trace twice, none missing");
        assertTrue(written >= facts, "the sum of the newly written counts covers this command's facts");
        assertEquals(publishBefore + 1, timerCount("publish"), 0.0001, "one publish latency, however many projectors ran");
        assertEquals(ackBefore + 1, timerCount("ack"), 0.0001, "one ACK latency, however many projectors ran");
        drainProjector();
        assertEquals(publishBefore + 1, timerCount("publish"), 0.0001, "a later pass records nothing again");
    }

    @Test
    @Timeout(120)
    void aDeviceClockInThePastOrFutureCannotChangeTheRecordedAckLatency() throws Exception {
        drainProjector();
        double before = timerCount("ack");
        UUID early = sendPublished();
        ack(early, "ACCEPTED", Instant.now().getEpochSecond() - 86_400, null);
        UUID late = sendPublished();
        ack(late, "ACCEPTED", Instant.now().getEpochSecond() + 86_400, null);
        drainProjector();
        var timer = meters.find("nexus.command.latency").tag("stage", "ack").timer();
        assertEquals(before + 2, timer.count(), 0.0001);
        assertTrue(timer.max(TimeUnit.SECONDS) < 60, "the largest recorded ACK latency is a backend-clock figure of a few seconds at most, not a day: "
                + timer.max(TimeUnit.SECONDS));
    }

    @Test
    @Timeout(120)
    void traceContentIsFixedCodesOnly() throws Exception {
        UUID id = sendPublished();
        ack(id, "REJECTED", Instant.now().getEpochSecond(), "SECRET-DEVICE-TEXT with spaces");
        drainProjector();
        Set<String> allowed = Set.of("commandId", "sequence", "fact", "from", "to", "attempt", "reason", "source", "sinceAcceptedMs");
        for (Map<String, Object> row : traces(id)) {
            String metadata = (String) row.get("metadata");
            assertFalse(metadata.contains("SECRET"), "device text is never copied into a trace: " + metadata);
            assertFalse(metadata.contains("nexus/devices"), metadata);
            Set<String> keys = new HashSet<>();
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata).fieldNames().forEachRemaining(keys::add);
            assertTrue(allowed.containsAll(keys), "only fixed keys: " + keys);
        }
    }

    @Test
    @Timeout(180)
    void aFailingProjectionNeverTouchesTheOutcomeAndTheNextPassCatchesUp() throws Exception {
        drainProjector();
        jdbc.execute("CREATE OR REPLACE FUNCTION p4f_block_command_traces() RETURNS trigger AS $$ BEGIN"
                + " IF NEW.trace_id LIKE 'cmd:%' THEN RAISE EXCEPTION 'blocked for the test'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER p4f_block BEFORE INSERT ON pipeline_traces FOR EACH ROW EXECUTE FUNCTION p4f_block_command_traces()");
        UUID id;
        String stateWhileBlocked;
        try {
            id = sendPublished();
            ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
            assertEquals(0, projector.runOnce(), "the failing pass is contained: it throws nothing and writes nothing");
            stateWhileBlocked = outboxState(id);
            assertEquals(0, traces(id).size());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS p4f_block ON pipeline_traces");
            jdbc.execute("DROP FUNCTION IF EXISTS p4f_block_command_traces()");
        }
        assertEquals("PUBLISHED", stateWhileBlocked, "the delivery outcome did not depend on the trace");
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, id));
        projector.runOnce();
        assertEquals(facts(id).size(), traces(id).size(), "the next pass recovers every missing trace");
    }

    @Test
    @Timeout(120)
    void aDeadLetterAndItsUncertaintyAreVisibleInTheTraces() throws Exception {
        UUID id = sendDeadLettered(BrokerCommandOutcome.UNCERTAIN);
        drainProjector();
        List<String> statuses = new ArrayList<>();
        traces(id).forEach(r -> statuses.add((String) r.get("status")));
        assertTrue(statuses.contains("ATTEMPT_UNCERTAIN"), statuses.toString());
        assertTrue(statuses.contains("DEAD_LETTERED"), statuses.toString());
        var dead = traces(id).stream().filter(r -> "DEAD_LETTERED".equals(r.get("status"))).findFirst().orElseThrow();
        assertTrue(((String) dead.get("metadata")).contains("UNCERTAIN_EXPIRED"), "the uncertain reason stays visible: " + dead.get("metadata"));
    }

    @Test
    @Timeout(180)
    void everyCommandAndTelemetryMeterUsesOnlyFixedLowCardinalityTags() throws Exception {
        UUID published = sendPublished();
        ack(published, "ACCEPTED", Instant.now().getEpochSecond(), null);
        UUID rejected = sendPublished();
        ack(rejected, "REJECTED", Instant.now().getEpochSecond(), "SECRET-DEVICE-TEXT");
        UUID timedOut = sendPublished();
        jdbc.update("UPDATE action_history SET ack_deadline_at = clock_timestamp() - INTERVAL '2 seconds' WHERE request_id = ?", timedOut);
        timeoutScanner.scan(50);
        sendDeadLettered(BrokerCommandOutcome.UNCERTAIN);
        drainProjector();

        java.util.regex.Pattern identifier = java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        java.util.regex.Pattern fixedValue = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,40}");
        Set<String> tagKeys = Set.of("stage", "outcome", "source", "scope", "origin", "state", "result", "reason", "status", "type", "area", "id",
                "application", "uri", "method", "exception", "error", "name", "quantile", "le", "action", "cause", "pool", "key", "class", "level");
        int audited = 0;
        for (var meter : meters.getMeters()) {
            String name = meter.getId().getName();
            if (!(name.startsWith("nexus.command") || name.startsWith("nexus.telemetry"))) continue;
            audited++;
            for (var tag : meter.getId().getTags()) {
                assertTrue(tagKeys.contains(tag.getKey()), name + " uses an unexpected tag key " + tag.getKey());
                assertFalse(identifier.matcher(tag.getValue()).find(), name + " tag " + tag.getKey() + " carries an identifier: " + tag.getValue());
                assertTrue(fixedValue.matcher(tag.getValue()).matches(), name + " tag " + tag.getKey() + " is not a fixed code: " + tag.getValue());
            }
        }
        assertTrue(audited >= 4, "the audit saw the command meters: " + audited);
        long distinctLatencyTagSets = meters.find("nexus.command.latency").timers().stream().map(t -> t.getId().getTags().toString()).distinct().count();
        assertTrue(distinctLatencyTagSets <= 3, "the latency timer has a bounded set of tag combinations: " + distinctLatencyTagSets);
    }

    @Test
    @Timeout(120)
    void aSteadyStatePassLooksOnlyAtRecentFactsAndTheFullSweepRecoversOlderOnes() throws Exception {
        drainProjector();
        UUID id = sendPublished();
        int facts = facts(id).size();
        Thread.sleep(2500);                                                    // the facts are now older than a two-second recent window
        assertEquals(0, projector.project(java.time.Duration.ofSeconds(2)), "a steady-state pass does not look back beyond its recent window");
        assertEquals(0, traces(id).size());
        projector.project();                                                   // the recovery sweep over the full lookback
        assertEquals(facts, traces(id).size(), "the sweep recovers every older fact");
    }
}
