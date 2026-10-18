package com.nexus.action.lifecycle;

import com.nexus.pipeline.application.PipelineService;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.domain.PipelineTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline-trace evidence for EVERY command lifecycle transition (contract R37, decision D3, ledger P4-095).
 *
 * <p>The append-only {@code command_state_history} is the single source of truth. This projector derives one trace per history fact AFTER
 * that fact committed, so a trace can never describe a change that rolled back, and it never takes part in, delays or changes the
 * transition it describes (a failing projector pass only logs and repeats on the next pass; delivery, ACK and timeout handling are
 * untouched). The trace identity is STABLE and derived: {@code cmd:<commandId>:<sequence>} with the fact's own instant as the start
 * timestamp, so
 * <ul>
 * <li>the projection is idempotent: {@code ON CONFLICT DO NOTHING} on the trace primary key, therefore running it twice, in two
 *     backend instances at once, or again after a crash writes nothing twice;</li>
 * <li>it is crash-recoverable from history alone: a fact whose trace is missing (the process died between the fact and the trace) is
 *     found by an anti-join on the next pass and projected then. The trace is therefore EVENTUALLY consistent with history: it can lag by
 *     one scan interval (default 2 s) or by the downtime of every instance, never by more than the lookback window (default 7 days,
 *     longer than the 168 h dedupe window; older facts are not back-filled);</li>
 * <li>work per pass is bounded ({@value #BATCH} facts) and the anti-join is bounded by the lookback.</li>
 * </ul>
 * Trace content is fixed codes only: the fact type, states, attempt, reason code and source. Never a payload, a command parameter, a
 * device-supplied text or an exception message. Latency metrics are recorded from the same facts, once per newly inserted trace, using
 * ONLY backend-recorded instants (the fact instants are written by the dispatcher/ACK handler/scanner from the backend or database
 * clock), so a device clock that is wrong in either direction cannot change them.
 */
@Component
public class CommandTraceProjector {
    private static final Logger log = LoggerFactory.getLogger(CommandTraceProjector.class);
    static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PipelineService pipeline;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final Duration lookback;
    private final Duration recentWindow;
    private final Duration recoveryInterval;
    private volatile long lastRecoverySweepNanos;
    private volatile boolean recoverySweepDone;

    @Autowired
    public CommandTraceProjector(JdbcTemplate jdbc, PlatformTransactionManager transactions, PipelineService pipeline,
                                 ObjectProvider<MeterRegistry> meters,
                                 @Value("${nexus.command.lifecycle-scanners-enabled:true}") boolean enabled,
                                 @Value("${nexus.command.trace-lookback:PT168H}") Duration lookback,
                                 @Value("${nexus.command.trace-recent-window:PT10M}") Duration recentWindow,
                                 @Value("${nexus.command.trace-recovery-interval:PT10M}") Duration recoveryInterval) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.pipeline = pipeline;
        this.meters = meters.getIfAvailable();
        this.enabled = enabled;
        this.lookback = lookback;
        this.recentWindow = recentWindow;
        this.recoveryInterval = recoveryInterval;
    }

    @Scheduled(fixedDelayString = "${nexus.command.trace-projection-interval:PT2S}")
    void scheduled() {
        if (enabled) runOnce();
    }

    /**
     * One bounded drain that never throws: a failing pass only logs and is repeated by the next one. In steady state a pass looks only at the
     * facts of the last {@code trace-recent-window} (ten minutes), so its cost does not grow with the history; the full {@code trace-lookback}
     * (seven days) is scanned once after the process starts and then every {@code trace-recovery-interval}, which is what recovers a trace lost
     * to a crash or to downtime.
     */
    int runOnce() {
        int total = 0;
        try {
            boolean sweep = !recoverySweepDone || System.nanoTime() - lastRecoverySweepNanos >= recoveryInterval.toNanos();
            Duration window = sweep ? lookback : recentWindow;
            // Up to ten full batches per tick, so a backlog after downtime clears without a busy loop.
            for (int pass = 0; pass < 10; pass++) {
                int written = project(window);
                total += written;
                if (written < BATCH) {
                    if (sweep) { recoverySweepDone = true; lastRecoverySweepNanos = System.nanoTime(); }
                    break;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Command trace projection pass failed; it repeats on the next pass: {}", failure.toString());
        }
        return total;
    }

    /** Projects over the full lookback. @return the number of traces newly written by this call (a number below {@value #BATCH} means no backlog is left) */
    public int project() {
        return project(lookback);
    }

    int project(Duration window) {
        List<PipelineTrace> inserted = tx.execute(status -> insertMissing(window));
        if (inserted == null) return 0;
        for (PipelineTrace trace : inserted) {
            try {
                pipeline.publishTrace(trace);
            } catch (RuntimeException failure) {
                log.debug("Command trace broadcast failed (the trace is stored)", failure);
            }
        }
        return inserted.size();
    }

    private List<PipelineTrace> insertMissing(Duration window) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "WITH missing AS ("
                + " SELECT h.command_id, h.sequence, h.fact_type, h.from_state, h.to_state, h.attempt, " + LifecycleReasonCodes.sql("h") + " AS reason_code, h.source, h.occurred_at,"
                + "        COALESCE(h.correlation_id, h.command_id::text) AS correlation_id, a.device_id,"
                + "        (SELECT p.occurred_at FROM command_state_history p WHERE p.command_id = h.command_id AND p.sequence = h.sequence - 1) AS previous_at,"
                + "        (SELECT f.occurred_at FROM command_state_history f WHERE f.command_id = h.command_id ORDER BY f.sequence LIMIT 1) AS first_at,"
                + "        (SELECT q.occurred_at FROM command_state_history q WHERE q.command_id = h.command_id AND q.fact_type = 'PUBLISHED' ORDER BY q.sequence LIMIT 1) AS published_at"
                + " FROM command_state_history h JOIN action_history a ON a.request_id = h.command_id"
                + " WHERE h.occurred_at >= clock_timestamp() - make_interval(secs => ?)"
                + "   AND NOT EXISTS (SELECT 1 FROM pipeline_traces t WHERE t.trace_id = 'cmd:' || h.command_id::text || ':' || h.sequence::text"
                + "                   AND t.start_timestamp = h.occurred_at)"
                + " ORDER BY h.occurred_at, h.command_id, h.sequence LIMIT " + BATCH + "),"
                + " inserted AS ("
                + " INSERT INTO pipeline_traces (trace_id, start_timestamp, end_timestamp, correlation_id, device_id, stage, duration_ms, status, metadata)"
                + " SELECT 'cmd:' || m.command_id::text || ':' || m.sequence::text, m.occurred_at, m.occurred_at, m.correlation_id,"
                + "        m.device_id::text, 'ACTION',"
                + "        COALESCE(GREATEST(0, (extract(epoch FROM (m.occurred_at - m.previous_at)) * 1000)::bigint), 0),"
                + "        m.fact_type,"
                + "        jsonb_strip_nulls(jsonb_build_object('commandId', m.command_id::text, 'sequence', m.sequence, 'fact', m.fact_type,"
                + "            'from', m.from_state, 'to', m.to_state, 'attempt', m.attempt, 'reason', m.reason_code, 'source', m.source,"
                + "            'sinceAcceptedMs', GREATEST(0, (extract(epoch FROM (m.occurred_at - m.first_at)) * 1000)::bigint)))"
                + " FROM missing m ON CONFLICT DO NOTHING RETURNING trace_id)"
                + " SELECT m.*, (SELECT count(*) FROM inserted i WHERE i.trace_id = 'cmd:' || m.command_id::text || ':' || m.sequence::text) AS was_inserted FROM missing m",
                (double) window.toSeconds());
        List<PipelineTrace> inserted = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (((Number) row.get("was_inserted")).intValue() == 0) continue;       // another instance won the insert: it records the metric
            UUID commandId = (UUID) row.get("command_id");
            int sequence = ((Number) row.get("sequence")).intValue();
            Instant at = ((java.sql.Timestamp) row.get("occurred_at")).toInstant();
            String fact = (String) row.get("fact_type");
            recordLatency(fact, at, row);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("commandId", commandId.toString());
            metadata.put("sequence", sequence);
            metadata.put("fact", fact);
            inserted.add(new PipelineTrace("cmd:" + commandId + ":" + sequence, (String) row.get("correlation_id"),
                    row.get("device_id") == null ? null : row.get("device_id").toString(), PipelineStage.ACTION, PipelineStage.ACTION.getIndex(),
                    PipelineStage.ACTION.getPrevious(), PipelineStage.ACTION.getNext(), at.toEpochMilli(), at.toEpochMilli(), 0, fact, 0, metadata));
        }
        return inserted;
    }

    /** Fixed-name timers from backend instants only; a missing predecessor records nothing. */
    private void recordLatency(String fact, Instant at, Map<String, Object> row) {
        if (meters == null) return;
        Instant first = instant(row.get("first_at"));
        Instant published = instant(row.get("published_at"));
        switch (fact) {
            case "PUBLISHED" -> record("publish", first, at);
            case "ACK_ACCEPTED", "ACK_REJECTED" -> record("ack", published, at);
            default -> { /* other facts have no latency of their own */ }
        }
    }

    private void record(String stage, Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) return;
        Timer.builder("nexus.command.latency").tag("stage", stage)
                .description("Backend-clock latency of a command lifecycle stage (accepted to published, published to ACK)")
                .register(meters).record(Duration.between(from, to));
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof java.sql.Timestamp t ? t.toInstant() : null;
    }
}
