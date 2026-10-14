package com.nexus.rule.temporal;

import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** COUNT_WITHIN, TREND, ABSENCE_FOR and OFFLINE_FOR with a controlled Clock and persisted state only. */
class RuleOtherTemporalOperatorsIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired com.nexus.rule.trigger.RuleTriggerRouter routerBean;

    private UUID countRule(UUID device, long window, int count, TemporalSpec.CountMode mode) {
        return temporalRule(device, "count", new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, window, count, null, null, mode), true, RuleOperator.GT, "10", List.of(alert()));
    }

    private int members(UUID rule) { return sql.queryForObject("SELECT jsonb_array_length(members) FROM rule_temporal_state WHERE rule_id=? ORDER BY updated_at DESC LIMIT 1", Integer.class, rule); }

    // ------------------------------------------------------------------ COUNT_WITHIN

    @Test void countsDistinctQualifiedSamplesInTheHalfOpenWindow() {
        UUID device = device();
        UUID rule = countRule(device, 60, 3, TemporalSpec.CountMode.SAMPLES);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(30); ingest(device, 26, now);
        assertEquals(2, members(rule));
        assertEquals(0, matches(rule));
        now = t0.plusSeconds(60);                                  // window is (now - 60 s, now]: the t0 sample has just expired
        ingest(device, 27, now);
        assertEquals(2, members(rule), "t0 evicted exactly at the window edge; t0+30 and t0+60 remain");
        assertEquals(0, matches(rule));
        now = t0.plusSeconds(61); ingest(device, 28, now);         // t0+30, t0+60, t0+61 are inside (t0+1, t0+61]
        assertEquals(1, matches(rule));
        assertEquals(1, dueFacts(rule));
        assertEquals(1, temporalEvents(rule, "SATISFIED"));
        assertEquals(0, members(rule), "satisfied occurrences are consumed, not re-counted");
    }

    @Test void duplicateSourceIdentitiesNeverIncreaseTheCount() {
        UUID device = device();
        UUID rule = countRule(device, 600, 3, TemporalSpec.CountMode.SAMPLES);
        Instant t0 = now;
        ingest(device, 25, t0);
        UUID first = sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='ACCEPTED_TELEMETRY' AND device_id=?", UUID.class, device);
        now = t0.plusSeconds(5); ingest(device, 26, now);
        routerBean.route(first);                                    // duplicate delivery of an already counted source fact
        routerBean.route(first);
        assertTrue(members(rule) <= 2, "the same source identity is counted once");
        assertEquals(0, matches(rule));
    }

    @Test void edgeModeCountsPredicateTransitionsWhileSampleModeCountsSamples() {
        UUID device = device();
        UUID edges = countRule(device, 600, 2, TemporalSpec.CountMode.EDGES);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(5); ingest(device, 26, now);          // still true: not an edge
        now = t0.plusSeconds(10); ingest(device, 27, now);
        assertEquals(1, members(edges), "three true samples form one edge");
        assertEquals(0, matches(edges));
        now = t0.plusSeconds(15); ingest(device, 3, now);          // false
        now = t0.plusSeconds(20); ingest(device, 28, now);         // second edge
        assertEquals(1, matches(edges));
    }

    @Test void nonGoodEvidenceResetsCountProgressWithoutAFalseEdge() {
        UUID device = device();
        UUID rule = countRule(device, 600, 3, TemporalSpec.CountMode.SAMPLES);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(5);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        assertEquals(0, members(rule));
        assertEquals(1, temporalEvents(rule, "RESET"));
    }

    // ------------------------------------------------------------------ TREND

    private UUID trendRule(UUID device, TemporalSpec.Direction direction, String minChange) {
        return temporalRule(device, "trend", new TemporalSpec(TemporalOperator.TREND, 40L, 300L, null, direction, new BigDecimal(minChange)), true, RuleOperator.GT, "0", List.of(alert()));
    }

    @Test void risingTrendNeedsThreeDistinctSamplesSpanAndMagnitude() {
        UUID device = device();
        UUID rule = trendRule(device, TemporalSpec.Direction.RISING, "5");
        Instant t0 = now;
        ingest(device, 20, t0);
        now = t0.plusSeconds(20); ingest(device, 22, now);
        assertEquals(0, matches(rule), "two samples never establish a trend");
        now = t0.plusSeconds(30); ingest(device, 27, now);         // three samples, span 30 s < 40 s
        assertEquals(0, matches(rule));
        now = t0.plusSeconds(40); ingest(device, 28, now);         // span 40 s, change 8 >= 5, monotonic
        assertEquals(1, matches(rule), sql.queryForList("SELECT event,details::text FROM rule_temporal_evidence WHERE rule_id=? ORDER BY seq", rule).toString());
        assertEquals(1, temporalEvents(rule, "SATISFIED"));
        String details = sql.queryForObject("SELECT details::text FROM rule_temporal_evidence WHERE rule_id=? AND event='SATISFIED'", String.class, rule);
        assertTrue(details.contains("spanSeconds") && details.contains("change"), details);
    }

    @Test void nonMonotonicOrWrongDirectionOrSmallChangeNeverSatisfiesATrend() {
        UUID device = device();
        UUID rule = trendRule(device, TemporalSpec.Direction.RISING, "5");
        Instant t0 = now;
        ingest(device, 20, t0);
        now = t0.plusSeconds(20); ingest(device, 30, now);
        now = t0.plusSeconds(40); ingest(device, 25, now);         // dip: net +5 but not monotonic
        assertEquals(0, matches(rule));
        UUID falling = trendRule(device, TemporalSpec.Direction.FALLING, "5");
        now = t0.plusSeconds(60); ingest(device, 26, now);
        assertEquals(0, matches(falling), "a rising series does not satisfy FALLING");
        UUID small = trendRule(device, TemporalSpec.Direction.RISING, "50");
        now = t0.plusSeconds(80); ingest(device, 27, now);
        assertEquals(0, matches(small), "magnitude below minChange");
    }

    @Test void lowQualityEvidenceInvalidatesTheTrendWindow() {
        UUID device = device();
        UUID rule = trendRule(device, TemporalSpec.Direction.RISING, "5");
        Instant t0 = now;
        ingest(device, 20, t0);
        now = t0.plusSeconds(20); ingest(device, 22, now);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        assertEquals(0, members(rule));
        assertTrue(temporalEvents(rule, "RESET") >= 1);
    }

    // ------------------------------------------------------------------ ABSENCE_FOR

    private UUID absenceRule(UUID device, long seconds) {
        return temporalRule(device, "absence", new TemporalSpec(TemporalOperator.ABSENCE_FOR, seconds, null, null, null, null), true, RuleOperator.EQ, "x", List.of(alert()));
    }

    @Test void absenceArmsAtActivationRearmsOnAcceptedObservationsAndRechecksTheWatermark() {
        UUID device = device();
        Instant t0 = now;
        UUID rule = absenceRule(device, 60);
        assertEquals("ARMED", phase(rule), "armed from activation even with no observation yet");
        now = t0.plusSeconds(30); ingest(device, 25, now);         // accepted newer observation rearms to t0 + 90
        ingest(device, 26, now);                                    // exact replay: rejected, no fact, no rearm
        assertEquals(1, temporalEvents(rule, "REARMED"));
        now = t0.plusSeconds(60);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule), "the original activation deadline was moved by the observation");
        now = t0.plusSeconds(90).minusMillis(1);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule));
        now = t0.plusSeconds(90);
        timers.processDue(10);
        assertEquals(1, dueFacts(rule));
        assertEquals(1, matches(rule));
        String details = sql.queryForObject("SELECT details::text FROM rule_temporal_evidence WHERE rule_id=? AND event='SATISFIED'", String.class, rule);
        assertTrue(details.contains("NO_OBSERVATION_SINCE_ARM"), "missing observations are an explicit evidence exception, never a fabricated GOOD sample");
    }

    @Test void absenceWithoutAnyObservationFiresOnceAtTheActivationDeadline() {
        UUID device = device();
        Instant t0 = now;
        UUID rule = absenceRule(device, 60);
        now = t0.plusSeconds(60);
        timers.processDue(10);
        assertEquals(1, dueFacts(rule));
        timers.processDue(10);
        assertEquals(1, dueFacts(rule), "satisfied once; it does not repeat while the silence continues");
        assertEquals("SATISFIED", phase(rule));
    }

    // ------------------------------------------------------------------ OFFLINE_FOR

    private UUID offlineRule(UUID device, long seconds) {
        return temporalRule(device, "offline", new TemporalSpec(TemporalOperator.OFFLINE_FOR, seconds, null, null, null, null), false, RuleOperator.EQ, "OFFLINE", List.of(alert()));
    }

    @Test void observedOfflineArmsOnlineResetsAndUnknownNeverCounts() {
        UUID device = device();                                     // ONLINE
        UUID rule = offlineRule(device, 60);
        Instant t0 = now;
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        assertEquals("ARMED", phase(rule));
        now = t0.plusSeconds(30);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE);
        assertEquals("IDLE", phase(rule));
        assertEquals(0, pendingTimers(rule));
        now = t0.plusSeconds(40);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        now = t0.plusSeconds(99);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule));
        now = t0.plusSeconds(100);
        timers.processDue(10);
        assertEquals(1, dueFacts(rule));
        assertEquals(1, matches(rule));
        String details = sql.queryForObject("SELECT details::text FROM rule_temporal_evidence WHERE rule_id=? AND event='SATISFIED'", String.class, rule);
        assertTrue(details.contains("not electrical or physical truth"), details);
    }

    @Test void aDeviceNeverObservedHasUnknownConnectivityNotOffline() {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID();
        sql.update("INSERT INTO sites(id,name) VALUES(?,'P33')", site);
        sql.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'P33')", building, site);
        sql.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'P33',now(),now())", space, building);
        sql.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES(?,?,'P33','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')", device, space, device.toString());
        UUID rule = offlineRule(device, 60);
        Instant t0 = now;
        now = t0.plusSeconds(3600);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule), "UNKNOWN is not OFFLINE");
        assertEquals(0, matches(rule));
        assertEquals("IDLE", phase(rule));
    }

    @Test void activatingWhileAlreadyObservedOfflineArmsNoEarlierThanActivation() {
        UUID device = device();
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        now = now.plusSeconds(500);                                 // long offline before the rule exists
        UUID rule = offlineRule(device, 60);
        Instant activated = now;
        assertEquals("ARMED", phase(rule));
        now = activated.plusSeconds(59);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule), "offline time before activation does not count");
        now = activated.plusSeconds(60);
        timers.processDue(10);
        assertEquals(1, dueFacts(rule));
    }
}
