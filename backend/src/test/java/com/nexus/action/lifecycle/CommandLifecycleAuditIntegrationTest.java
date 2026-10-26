package com.nexus.action.lifecycle;

import com.nexus.action.outbox.BrokerCommandPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.nexus.action.outbox.CommandStateHistory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit of the append-only command history (ledger P4-094, P4-096): every transition has a fact with a correlation id, a source and a
 * backend instant, the facts chain without gaps, and one correlation id joins acceptance, attempts, publication, the ACK and the traces.
 */
class CommandLifecycleAuditIntegrationTest extends LifecycleFixture {
    @Autowired CommandTraceProjector projector;
    @Autowired CommandStateHistory stateHistory;
    @Autowired PlatformTransactionManager transactions;

    private List<Map<String, Object>> historyRows(UUID id) {
        return jdbc.queryForList("SELECT sequence, fact_type, from_state, to_state, attempt, source, correlation_id, occurred_at,"
                + " (occurred_at <= clock_timestamp() + INTERVAL '1 second') AS not_in_future FROM command_state_history WHERE command_id = ? ORDER BY sequence", id);
    }

    @Test
    @Timeout(180)
    void everyTransitionOfARetriedCommandIsAFactWithCorrelationSourceAndBackendTimeThatChainsWithoutGaps() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        broker.behaviour = (t, b) -> calls.incrementAndGet() == 1 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED")
                : BrokerCommandPublisher.Attempt.confirmed();
        UUID id = sendCommand();
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication after one retry", 60);

        List<Map<String, Object>> rows = historyRows(id);
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "PUBLISHED"),
                rows.stream().map(r -> (String) r.get("fact_type")).toList());
        Instant createdAt = jdbc.queryForObject("SELECT created_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id).toInstant();
        Set<String> sources = Set.of("ACCEPTANCE", "DISPATCHER", "ACK_HANDLER", "TIMEOUT_SCANNER", "RECONCILER", "MIGRATION");
        String previousTo = null;
        Instant previousAt = null;
        int sequence = 0;
        for (Map<String, Object> row : rows) {
            assertEquals(++sequence, ((Number) row.get("sequence")).intValue(), "sequence numbers are gapless");
            assertNotNull(row.get("correlation_id"), "correlation id on " + row.get("fact_type"));
            assertTrue(sources.contains((String) row.get("source")), "a known source on " + row.get("fact_type"));
            assertTrue((Boolean) row.get("not_in_future"), "a backend instant, never ahead of the database clock");
            Instant at = ((java.sql.Timestamp) row.get("occurred_at")).toInstant();
            assertFalse(at.isBefore(createdAt.minusSeconds(2)), "no fact is older than the acceptance it belongs to");
            if (previousAt != null) assertFalse(at.isBefore(previousAt.minusMillis(1)), "time never runs backwards within a command: " + row.get("fact_type") + " at " + at + " after " + previousAt + " in " + rows);
            previousAt = at;
            String from = (String) row.get("from_state"), to = (String) row.get("to_state");
            if (from != null && previousTo != null) assertEquals(previousTo, from, "each transition starts where the previous one ended");
            if (to != null) previousTo = to;
        }
        assertEquals("ACCEPTANCE", rows.get(0).get("source"));
        assertEquals("DISPATCHER", rows.get(2).get("source"));
    }

    @Test
    @Timeout(180)
    void oneCorrelationIdJoinsAcceptanceAttemptsPublicationTheAckAndTheTraces() throws Exception {
        UUID id = sendPublished();
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        String outboxCorrelation = jdbc.queryForObject("SELECT correlation_id FROM command_outbox WHERE command_id = ?", String.class, id);
        assertNotNull(outboxCorrelation);

        Set<String> correlations = new HashSet<>();
        for (Map<String, Object> row : historyRows(id)) correlations.add((String) row.get("correlation_id"));
        assertEquals(Set.of(outboxCorrelation), correlations, "acceptance, attempt, publication and ACK facts all carry the command's correlation id: " + correlations);

        for (int i = 0; i < 20 && projector.project() > 0; i++) { /* drain */ }
        List<String> traceCorrelations = jdbc.queryForList("SELECT DISTINCT correlation_id FROM pipeline_traces WHERE trace_id LIKE ?", String.class, "cmd:" + id + ":%");
        assertEquals(List.of(outboxCorrelation), traceCorrelations, "and so do the pipeline traces of the same command");
    }

    @Test
    @Timeout(120)
    void theStoredFactTimeIsAlwaysTheDatabaseClockWhateverInstantTheCallerPasses() throws Exception {
        UUID id = sendPublished();
        for (Instant claimed : List.of(Instant.now().plus(java.time.Duration.ofDays(400)), Instant.now().minus(java.time.Duration.ofDays(400)), Instant.EPOCH)) {
            Instant before = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
            new TransactionTemplate(transactions).executeWithoutResult(s ->
                    stateHistory.append(id, CommandStateHistory.Fact.ACK_CONFLICT_IGNORED, null, null, 1, "CONFLICTING_ACK", CommandStateHistory.Source.ACK_HANDLER,
                            "corr", claimed, "test of the time authority"));
            Instant after = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
            Instant stored = jdbc.queryForObject("SELECT occurred_at FROM command_state_history WHERE command_id = ? ORDER BY sequence DESC LIMIT 1",
                    java.sql.Timestamp.class, id).toInstant();
            assertFalse(stored.isBefore(before.minusMillis(1)) || stored.isAfter(after.plusMillis(1)),
                    "stored " + stored + " must lie between the database clock readings " + before + " and " + after + " (caller claimed " + claimed + ")");
        }
    }
}
