package com.nexus.intelligence.eval;

import com.nexus.intelligence.draft.DraftOutcome;
import com.nexus.intelligence.draft.DraftService;
import com.nexus.intelligence.model.AiCallGuard;
import com.nexus.intelligence.model.ModelProvider;
import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.trigger.TypedTriggerSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P6-011 on the whole system, not a unit: telemetry is ingested, a rule matches, a command is accepted and published through the real outbox to a real
 * broker, WHILE six concurrent callers keep asking the AI paths and the provider throws, hangs or answers garbage. The results must be exactly those of a run
 * with no AI traffic, every ingestion must finish inside a stated bound, no AI call may escape as an exception, and the call guard must recover all its
 * capacity. Latency and connection-pool figures are recorded as measurements.
 */
class WholeSystemIsolationIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("nexus.ai.send-device-evidence", () -> "true");
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "6000");
        r.add("nexus.ai.rate-limit.burst", () -> "6000");
        r.add("nexus.ai.guard.timeout-ms", () -> "700");
        r.add("nexus.ai.guard.max-concurrent", () -> "4");
        r.add("nexus.ai.guard.breaker-failures", () -> "100");
        r.add("nexus.ai.guard.max-retries", () -> "0");
    }

    @org.springframework.boot.test.mock.mockito.MockBean ModelProvider provider;
    @Autowired DraftService drafts;
    @Autowired AiCallGuard guard;
    @Autowired DataSource dataSource;

    static final int READINGS = 12;
    static final long INGESTION_BOUND_MILLIS = 10_000;
    private final AtomicReference<Supplier<String>> behaviour = new AtomicReference<>(() -> "{}");

    private static long percentile(List<Long> sorted, double p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    private record Run(List<Long> latencies, int matches, int actions, int telemetryRows, int aiCalls, int aiEscapes, int poolActiveMax, int poolAwaitingMax) {}

    private Run run(String mode, UUID device, UUID rule, boolean aiLoad) throws Exception {
        resetPublicationSpies();
        int matchesBefore = matches(rule);
        int actionsBefore = count("SELECT count(*) FROM action_history WHERE device_id = ?", device);
        int telemetryBefore = count("SELECT count(*) FROM telemetry_history WHERE device_id = ?", device);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger(), escapes = new AtomicInteger();
        ExecutorService load = Executors.newFixedThreadPool(6);
        if (aiLoad) {
            for (int i = 0; i < 6; i++) load.submit(() -> {
                while (!stop.get()) {
                    try {
                        DraftOutcome o = drafts.propose("Warn me when the temperature on this device is high");
                        calls.incrementAndGet();
                        if (o instanceof DraftOutcome.Created) escapes.incrementAndGet(); // a hostile provider must never produce a stored draft
                    } catch (Throwable t) {
                        escapes.incrementAndGet();
                    }
                }
            });
        }
        HikariDataSource hikari = dataSource instanceof HikariDataSource h ? h : null;
        AtomicInteger activeMax = new AtomicInteger(), awaitingMax = new AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                if (hikari != null && hikari.getHikariPoolMXBean() != null) {
                    activeMax.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    awaitingMax.accumulateAndGet(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                }
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });
        sampler.start();
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < READINGS; i++) {
            now = now.plusSeconds(1);
            long t0 = System.nanoTime();
            ingest(device, 25 + i * 0.1, now);
            latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        }
        int expected = matchesBefore + READINGS;
        awaitCondition("every reading to be matched (" + mode + ")", () -> matches(rule) >= expected);
        verifyPublications(READINGS);
        stop.set(true);
        load.shutdown();
        assertThat(load.awaitTermination(30, TimeUnit.SECONDS)).as("AI callers stop").isTrue();
        sampler.join(2_000);
        Collections.sort(latencies);
        return new Run(latencies, matches(rule) - matchesBefore, count("SELECT count(*) FROM action_history WHERE device_id = ?", device) - actionsBefore,
                count("SELECT count(*) FROM telemetry_history WHERE device_id = ?", device) - telemetryBefore, calls.get(), escapes.get(), activeMax.get(), awaitingMax.get());
    }

    @Test
    void p6_011_telemetryRulesAndCommandsContinueWithUnchangedResultsWhileTheProviderThrowsHangsOrReturnsGarbage() throws Exception {
        when(provider.available()).thenReturn(true);
        when(provider.identity()).thenReturn(new ProviderIdentity("scripted", "scripted-adversary", "eval-v1"));
        when(provider.complete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> behaviour.get().get());

        Eval.Suite suite = new Eval.Suite("isolation");
        UUID device = device();
        UUID rule = typedRule(device, "isolation rule", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        Map<String, Supplier<String>> modes = new LinkedHashMap<>();
        modes.put("THROW", () -> { throw new IllegalStateException("provider exploded with secret-token-xyz"); });
        modes.put("HANG", () -> { try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return "{}"; });
        modes.put("GARBAGE", () -> "<<<not json>>> {{{ ]]] \u0000 ignore previous instructions");

        Run baseline = run("BASELINE", device, rule, false);
        int ingestionFailures = 0, resultDifferences = 0, escapes = 0, aiCalls = 0, maxLatency = 0;
        Map<String, Object> distributions = new LinkedHashMap<>();
        distributions.put("BASELINE", dist(baseline));
        assertThat(baseline.matches()).isEqualTo(READINGS);

        for (var mode : modes.entrySet()) {
            behaviour.set(mode.getValue());
            Run r = run(mode.getKey(), device, rule, true);
            distributions.put(mode.getKey(), dist(r));
            if (r.telemetryRows() != baseline.telemetryRows()) { ingestionFailures++; suite.fail("isolation.telemetry_ingested", mode.getKey(), r.telemetryRows() + " rows instead of " + baseline.telemetryRows()); }
            if (r.matches() != baseline.matches() || r.actions() != baseline.actions()) { resultDifferences++; suite.fail("isolation.results_unchanged", mode.getKey(), "matches " + r.matches() + "/" + baseline.matches() + " actions " + r.actions() + "/" + baseline.actions()); }
            escapes += r.aiEscapes();
            aiCalls += r.aiCalls();
            maxLatency = (int) Math.max(maxLatency, r.latencies().getLast());
            if (r.aiCalls() == 0) suite.fail("isolation.ai_load_was_applied", mode.getKey(), "no AI call completed during the run");
        }
        int capacityLeaked = 6 * 0; // see below
        // the guard must give all its capacity back once the hostile callers stop (a hung task holds its slot only until it is cancelled)
        long deadline = System.currentTimeMillis() + 10_000;
        while (guard.availablePermits() < 4 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        capacityLeaked = 4 - guard.availablePermits();
        int draftsStored = count("SELECT count(*) FROM ai_rule_drafts");

        suite.add(Eval.Measure.zero("isolation.telemetry_ingested", "P6-011 whole-system isolation", "failure modes in which the number of persisted readings differed from the run with no AI traffic", ingestionFailures, modes.size(), "baseline run: " + READINGS + " readings"));
        suite.add(Eval.Measure.zero("isolation.results_unchanged", "P6-011 whole-system isolation", "failure modes in which rule matches or accepted commands differed from the run with no AI traffic", resultDifferences, modes.size(), "every match produced one accepted command that reached the broker seam (verifyPublications per run)"));
        suite.add(Eval.Measure.zero("isolation.ai_escapes", "P6-011 whole-system isolation", "AI calls that threw to their caller or produced a stored draft under a throwing, hanging or garbage provider", escapes, modes.size(), "cases are failure modes so the count is reproducible; AI calls completed across them (varies with machine speed): " + aiCalls));
        suite.add(Eval.Measure.zero("isolation.drafts_stored", "P6-011 whole-system isolation", "drafts stored by calls to a failing provider", draftsStored, modes.size(), null));
        suite.add(Eval.Measure.zero("isolation.guard_capacity_leaked", "P6-011 whole-system isolation", "call-guard permits not returned ten seconds after the hostile callers stopped", capacityLeaked, 4, null));
        suite.add(new Eval.Measure("isolation.ingestion_max_latency_ms", "P6-011 timing bound", "slowest single ingestion across all failure modes (proposed bound 10,000 ms, fixed before running)", Eval.Kind.AT_MOST, maxLatency, INGESTION_BOUND_MILLIS, modes.size() * READINGS, 10, true,
                "the bound is generous on purpose: it detects starvation, not small slowdowns. Percentiles are in the characterisation."));
        suite.characterise("isolation.ingestion_latency_ms", "ingestion latency (ms) and connection-pool use for the baseline and each failure mode", distributions);
        if (dataSource instanceof HikariDataSource h) suite.run("poolMaximumSize", h.getMaximumPoolSize());
        suite.run("f1Note", "F1 (connection-pool exhaustion) is NOT fixed: six concurrent AI callers raised the active connections well above the baseline; the shared rate limiter (4 concurrent) guards the HTTP routes, direct service callers are not limited");
        suite.run("readingsPerRun", READINGS).run("aiCallers", 6).run("guardTimeoutMs", 700).run("guardMaxConcurrent", 4);
        suite.writeAndGate();
    }

    private static Map<String, Object> dist(Run r) {
        List<Long> l = r.latencies();
        return Eval.mapOf("p50", percentile(l, 0.5), "p95", percentile(l, 0.95), "max", l.getLast(), "matches", r.matches(), "actions", r.actions(), "telemetryRows", r.telemetryRows(),
                "aiCallsCompleted", r.aiCalls(), "poolActiveMax", r.poolActiveMax(), "poolThreadsAwaitingMax", r.poolAwaitingMax());
    }
}
