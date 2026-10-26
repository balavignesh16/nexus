package com.nexus.intelligence.eval;

import com.nexus.intelligence.anomaly.AnomalyReport;
import com.nexus.intelligence.anomaly.AnomalyService;
import com.nexus.intelligence.recommend.RecommendationService;
import com.nexus.rule.trigger.TypedTriggerSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * P6-071 and the performance/reliability measurements of P6.5: request latency distribution of the anomaly and recommendation analysis under concurrent
 * telemetry ingestion, ingestion latency with and without analysis, connection-pool utilisation and saturation, heap use, response sizes, query bounds, and
 * refusal behaviour when the shared limiter is saturated. The figures are MEASUREMENTS of one machine and one run, not service-level targets: only
 * correctness (no failed ingestion, no failed analysis, bounded reads and responses, refusals as 429 and never as errors) is gated. F1 is not fixed here.
 */
@AutoConfigureMockMvc
class AnalysisPerformanceIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "6000");
        r.add("nexus.ai.rate-limit.burst", () -> "6000");
        r.add("nexus.ai.rate-limit.concurrency-limit", () -> "2");
    }

    @Autowired AnomalyService anomalies;
    @Autowired RecommendationService recommendations;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    static final int READINGS = 40;
    static final long MAX_RESPONSE_BYTES = 2_000_000;

    private static long pct(List<Long> sorted, double p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    private static Map<String, Object> dist(List<Long> millis) {
        List<Long> l = new ArrayList<>(millis);
        Collections.sort(l);
        return Eval.mapOf("n", l.size(), "p50", pct(l, 0.5), "p95", pct(l, 0.95), "p99", pct(l, 0.99), "max", l.isEmpty() ? 0 : l.getLast());
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    @Test
    void p6_071_analysisUnderConcurrentIngestionIsMeasuredAndBoundedAndRefusesRatherThanFails() throws Exception {
        Eval.Suite suite = new Eval.Suite("performance");
        UUID busy = device();
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) batch.add(new Object[] {UUID.randomUUID(), busy, java.sql.Timestamp.from(now.minusSeconds(10L + 15L * (5_000 - i))), "TEMPERATURE", 21.0 + (i % 7) * 0.1, "CELSIUS", "temperature", "NUMBER", 21.0 + (i % 7) * 0.1});
        sql.batchUpdate("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)", batch);
        for (int i = 0; i < 15; i++) {
            List<Object[]> extra = new ArrayList<>();
            for (int k = 0; k < 20; k++) extra.add(new Object[] {UUID.randomUUID(), busy, java.sql.Timestamp.from(now.minusSeconds(60L * (k + 1))), "CAP" + i, 1.0 + k, "X", "cap_" + (char) ('a' + i), "NUMBER", 1.0 + k});
            sql.batchUpdate("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)", extra);
        }
        UUID ingesting = device();

        // ---- phase A: ingestion alone
        List<Long> alone = new ArrayList<>();
        for (int i = 0; i < READINGS; i++) {
            now = now.plusSeconds(1);
            long t0 = System.nanoTime();
            ingest(ingesting, 21 + (i % 5) * 0.1, now);
            alone.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        }

        // ---- phase B: the same ingestion while four callers analyse continuously
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger analysisFailures = new AtomicInteger(), boundBreaches = new AtomicInteger();
        AtomicLong maxResponse = new AtomicLong(), maxRawRows = new AtomicLong();
        List<Long> analysisMillis = Collections.synchronizedList(new ArrayList<>());
        List<Long> recommendMillis = Collections.synchronizedList(new ArrayList<>());
        ExecutorService analysts = Executors.newFixedThreadPool(4);
        long heapBefore = usedHeapMb();
        AtomicLong heapPeak = new AtomicLong(heapBefore);
        HikariDataSource hikari = dataSource instanceof HikariDataSource h ? h : null;
        AtomicInteger activeMax = new AtomicInteger(), awaitingMax = new AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                heapPeak.accumulateAndGet(usedHeapMb(), Math::max);
                if (hikari != null && hikari.getHikariPoolMXBean() != null) {
                    activeMax.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    awaitingMax.accumulateAndGet(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                }
                try { Thread.sleep(25); } catch (InterruptedException e) { return; }
            }
        });
        sampler.start();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final boolean wantRecommendations = t % 2 == 1;
            futures.add(analysts.submit(() -> {
                while (!stop.get()) {
                    try {
                        long t0 = System.nanoTime();
                        if (wantRecommendations) {
                            var rep = recommendations.recommend(Optional.of(busy), Optional.empty(), Optional.empty(), Optional.empty());
                            recommendMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                            maxResponse.accumulateAndGet(mapper.writeValueAsBytes(rep).length, Math::max);
                        } else {
                            AnomalyReport rep = anomalies.analyse(busy, Optional.empty(), Optional.of(30), Optional.empty());
                            analysisMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                            maxResponse.accumulateAndGet(mapper.writeValueAsBytes(rep).length, Math::max);
                            for (var f : rep.findings()) {
                                long raw = ((Number) f.quality().get("rawRows")).longValue();
                                maxRawRows.accumulateAndGet(raw, Math::max);
                                if (raw > anomalies.settings().maxSamples()) boundBreaches.incrementAndGet();
                            }
                            if (rep.capabilitiesAnalysed().size() > anomalies.settings().maxCapabilities()) boundBreaches.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        analysisFailures.incrementAndGet();
                    }
                }
            }));
        }
        List<Long> under = new ArrayList<>();
        int ingestionFailures = 0;
        for (int i = 0; i < READINGS; i++) {
            now = now.plusSeconds(1);
            long t0 = System.nanoTime();
            try {
                ingest(ingesting, 21 + (i % 5) * 0.1, now);
            } catch (RuntimeException e) {
                ingestionFailures++;
            }
            under.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        }
        stop.set(true);
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        analysts.shutdown();
        sampler.join(2_000);
        long heapAfter = usedHeapMb();
        int persisted = count("SELECT count(*) FROM telemetry_history WHERE device_id = ?", ingesting);

        // ---- phase C: HTTP, with the shared limiter set to two concurrent requests: refusals must be 429, never an error
        ExecutorService http = Executors.newFixedThreadPool(8);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < 24; i++) statuses.add(http.submit(() -> mvc.perform(get("/api/v1/intelligence/anomalies/devices/" + busy).param("windowHours", "30")).andReturn().getResponse().getStatus()));
        int ok = 0, refused = 0, other = 0;
        for (Future<Integer> s : statuses) {
            int status = s.get(60, TimeUnit.SECONDS);
            if (status == 200) ok++;
            else if (status == 429) refused++;
            else other++;
        }
        http.shutdown();

        suite.add(Eval.Measure.zero("perf.ingestion_failures", "P6-071 ingestion unaffected", "ingestions that failed while analyses ran concurrently, or readings not persisted", ingestionFailures + Math.abs(2 * READINGS - persisted), 2 * READINGS, null));
        suite.add(Eval.Measure.zero("perf.analysis_failures", "P6-071 analysis reliability", "analysis or recommendation requests that threw under concurrent ingestion", analysisFailures.get(), Math.max(1, analysisMillis.size() + recommendMillis.size()), null));
        suite.add(Eval.Measure.zero("perf.read_bounds_breached", "P6-071 query bounds", "analyses that read more rows than max-samples or analysed more capabilities than max-capabilities", boundBreaches.get(), Math.max(1, analysisMillis.size()), "max rows read per capability observed: " + maxRawRows.get()));
        suite.add(new Eval.Measure("perf.response_size_max_bytes", "P6-071 response bound", "largest serialised analysis or recommendation response (proposed bound 2,000,000 bytes, fixed before running)", Eval.Kind.AT_MOST, maxResponse.get(), MAX_RESPONSE_BYTES, 1, 1, true, null));
        suite.add(Eval.Measure.zero("perf.refusal_is_429_not_an_error", "P6-071 refusal behaviour", "requests that were neither served (200) nor refused by the limiter (429) when 24 requests hit a limit of 2 concurrent", other, 24, "served " + ok + ", refused " + refused));

        Map<String, Object> figures = Eval.mapOf(
                "ingestionAloneMs", dist(alone), "ingestionWithAnalysisMs", dist(under), "anomalyAnalysisMs", dist(analysisMillis), "recommendationMs", dist(recommendMillis),
                "analysesCompleted", analysisMillis.size(), "recommendationsCompleted", recommendMillis.size(),
                "poolMaximumSize", hikari == null ? null : hikari.getMaximumPoolSize(), "poolActiveMax", activeMax.get(), "poolThreadsAwaitingMax", awaitingMax.get(),
                "heapUsedMbBefore", heapBefore, "heapUsedMbPeak", heapPeak.get(), "heapUsedMbAfter", heapAfter,
                "maxResponseBytes", maxResponse.get(), "maxRowsReadPerCapability", maxRawRows.get(), "limiterServed", ok, "limiterRefused", refused);
        suite.characterise("perf.figures", "latency distribution, connection pool, heap, response size and limiter behaviour; ONE machine, ONE run; measurements, not targets", figures);
        suite.run("readingsPerPhase", READINGS).run("analysts", 4).run("statementTimeoutMs", Map.of("anomaly", 5000, "recommendation", 8000))
                .run("f1Note", "F1 (connection-pool exhaustion) is NOT fixed: poolActiveMax shows how much of the pool concurrent analysis can take");
        suite.writeAndGate();
        assertThat(ok + refused).isEqualTo(24);
        assertThat(Duration.ofMillis(under.stream().mapToLong(Long::longValue).max().orElse(0)).toSeconds()).isLessThan(30);
    }
}
