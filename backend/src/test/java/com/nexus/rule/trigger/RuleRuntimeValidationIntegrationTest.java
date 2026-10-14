package com.nexus.rule.trigger;

import com.nexus.device.capability.CapabilityType;
import com.nexus.rule.application.RuleCapabilityValidator;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** One validation authority for trigger subscriptions, schedules, repeat policy and temporal configuration (C08/C12/C15/C18). */
class RuleRuntimeValidationIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleCapabilityValidator validator;

    private Rule rule(UUID device, RuleRuntime runtime, RuleCondition... conditions) {
        var all = new ArrayList<RuleCondition>();
        if (device != null) all.add(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()));
        all.addAll(List.of(conditions));
        return new Rule(UUID.randomUUID(), "validated", true, 1, all, List.of(alert()), Map.of(), null, null, runtime);
    }
    private static RuleRuntime on(TriggerSubscription... triggers) { return new RuleRuntime(List.of(triggers), null, null); }
    private static TriggerSubscription sub(TriggerType type, String... filter) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < filter.length; i += 2) map.put(filter[i], filter[i + 1]);
        return new TriggerSubscription(type, map);
    }
    private Set<String> codes(Rule rule) {
        var set = new TreeSet<String>();
        validator.inspect(rule).forEach(e -> set.add(e.get("code").toString()));
        return set;
    }
    private static RuleCondition temporal(TemporalSpec spec, String id) {
        return new RuleCondition(RuleField.VALUE, RuleOperator.GT, "10", CapabilityType.TEMPERATURE, null, null, id, spec);
    }
    private static RuleRuntime due() { return on(sub(TriggerType.TEMPORAL_DUE)); }

    @Test void validTriggerConfigurationsHaveNoDiagnostics() {
        UUID d = device();
        assertEquals(Set.of(), codes(rule(d, on(sub(TriggerType.ACCEPTED_TELEMETRY, "capabilityKey", "temperature")), temperatureAbove("10"))));
        assertEquals(Set.of(), codes(rule(d, on(sub(TriggerType.CONNECTIVITY_CHANGED, "connectivity", "OFFLINE")))));
        assertEquals(Set.of(), codes(rule(d, on(sub(TriggerType.VERIFICATION_CHANGED, "status", "CONVERGED"), sub(TriggerType.VERIFICATION_TIMED_OUT)))));
        assertEquals(Set.of(), codes(rule(d, on(sub(TriggerType.RULE_MATCHED, "parentRuleId", UUID.randomUUID().toString())))));
        assertEquals(Set.of(), codes(rule(null, on(sub(TriggerType.COMMAND_ACK_REJECTED)), new RuleCondition(RuleField.VALUE, RuleOperator.GT, "1", CapabilityType.TEMPERATURE))),
            "TRIGGER_DEVICE scope is valid without a declared device");
    }

    @Test void triggerFiltersAreValidatedPerFamilyAndDuplicatesRejected() {
        UUID d = device();
        assertTrue(codes(rule(d, on(sub(TriggerType.ACCEPTED_TELEMETRY, "status", "CONVERGED")))).contains("UNSUPPORTED_TRIGGER_FILTER"));
        assertTrue(codes(rule(d, on(sub(TriggerType.VERIFICATION_CHANGED, "status", "BROKEN")))).contains("INVALID_TRIGGER_FILTER"));
        assertTrue(codes(rule(d, on(sub(TriggerType.CONNECTIVITY_CHANGED, "connectivity", "UNKNOWN")))).contains("INVALID_TRIGGER_FILTER"), "UNKNOWN is not an observed status");
        assertTrue(codes(rule(d, on(sub(TriggerType.RULE_MATCHED, "parentRuleId", "not-a-uuid")))).contains("INVALID_TRIGGER_FILTER"));
        assertTrue(codes(rule(d, on(sub(TriggerType.VERIFICATION_TIMED_OUT, "status", "TIMED_OUT")))).contains("UNSUPPORTED_TRIGGER_FILTER"));
        assertTrue(codes(rule(d, on(sub(TriggerType.ACCEPTED_TELEMETRY), sub(TriggerType.ACCEPTED_TELEMETRY)))).contains("DUPLICATE_TRIGGER"));
    }

    @Test void typedTriggersCannotReadDomainEventPayloadAndManualAndScheduleNeedExplicitScope() {
        UUID d = device();
        assertTrue(codes(rule(d, on(sub(TriggerType.ACCEPTED_TELEMETRY)), new RuleCondition(RuleField.SEVERITY, RuleOperator.EQ, "INFO"))).contains("PAYLOAD_CONDITION_ON_TYPED_TRIGGER"));
        assertTrue(codes(rule(d, on(sub(TriggerType.ACCEPTED_TELEMETRY), sub(TriggerType.LEGACY_DOMAIN_EVENT)), new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"))).contains("PAYLOAD_CONDITION_ON_TYPED_TRIGGER"));
        assertTrue(codes(rule(null, on(sub(TriggerType.MANUAL)), temperatureAbove("10"))).contains("EXPLICIT_SCOPE_REQUIRED"));
        assertTrue(codes(rule(null, new RuleRuntime(List.of(sub(TriggerType.SCHEDULE)), null, new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, now.plusSeconds(5), null, null)), temperatureAbove("10"))).contains("EXPLICIT_SCOPE_REQUIRED"));
    }

    @Test void schedulesAreUtcOneShotOrFixedIntervalOnly() {
        UUID d = device();
        assertTrue(codes(rule(d, on(sub(TriggerType.SCHEDULE)))).contains("SCHEDULE_REQUIRED"));
        assertTrue(codes(rule(d, new RuleRuntime(List.of(sub(TriggerType.SCHEDULE)), null, new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, null, null, null)))).contains("INVALID_SCHEDULE"));
        assertTrue(codes(rule(d, new RuleRuntime(List.of(sub(TriggerType.SCHEDULE)), null, new ScheduleSpec(ScheduleSpec.Kind.FIXED_INTERVAL, null, 0L, null)))).contains("INVALID_SCHEDULE"));
        assertTrue(codes(rule(d, new RuleRuntime(List.of(sub(TriggerType.ACCEPTED_TELEMETRY)), null, new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, now, null, null)))).contains("SCHEDULE_WITHOUT_SUBSCRIPTION"));
        assertEquals(Set.of(), codes(rule(d, new RuleRuntime(List.of(sub(TriggerType.SCHEDULE)), RepeatPolicy.EDGE_TRUE, new ScheduleSpec(ScheduleSpec.Kind.FIXED_INTERVAL, null, 60L, null)))));
    }

    @Test void temporalConditionsRequireStableIdsBoundedParametersAndTemporalDueRouting() {
        UUID d = device();
        var duration = new TemporalSpec(TemporalOperator.FOR_DURATION, 60L, null, null, null, null);
        assertEquals(Set.of(), codes(rule(d, due(), temporal(duration, "c1"))));
        assertTrue(codes(rule(d, due(), temporal(duration, null))).contains("CONDITION_ID_REQUIRED"));
        assertTrue(codes(rule(d, due(), temporal(duration, "bad id!"))).contains("INVALID_CONDITION_ID"));
        assertTrue(codes(rule(d, due(), temporal(duration, "c1"), temporalPlain("c1"))).contains("DUPLICATE_CONDITION_ID"));
        assertTrue(codes(rule(d, due(), temporal(duration, "a"), temporal(duration, "b"))).contains("UNSUPPORTED_TEMPORAL_COMBINATION"));
        assertTrue(codes(rule(d, on(sub(TriggerType.ACCEPTED_TELEMETRY)), temporal(duration, "c1"))).contains("TEMPORAL_REQUIRES_TEMPORAL_DUE"));
        assertTrue(codes(rule(d, due(), temperatureAbove("10"))).contains("TEMPORAL_DUE_WITHOUT_CONDITION"));
        assertTrue(codes(rule(null, due(), temporal(duration, "c1"))).contains("EXPLICIT_SCOPE_REQUIRED"));
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.FOR_DURATION, 0L, null, null, null, null), "c1"))).contains("INVALID_TEMPORAL"));
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.FOR_DURATION, 100_000L, null, null, null, null), "c1"))).contains("INVALID_TEMPORAL"), "windows are bounded");
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, 60L, 0, null, null), "c1"))).contains("INVALID_TEMPORAL"), "positive configured count");
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, 60L, 5_000, null, null), "c1"))).contains("INVALID_TEMPORAL"), "bounded storage");
        assertEquals(200, TemporalSpec.MAX_COUNT, "one authoritative capacity shared with the V28 member cap");
        assertEquals(Set.of(), codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, 60L, TemporalSpec.MAX_COUNT, null, null), "c1"))), "the maximum is accepted");
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, 60L, TemporalSpec.MAX_COUNT + 1, null, null), "c1"))).contains("INVALID_TEMPORAL"), "one above the maximum is rejected before activation");
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.TREND, 40L, 300L, 201, TemporalSpec.Direction.RISING, new java.math.BigDecimal("5")), "t"))).contains("INVALID_TEMPORAL"), "TREND samples share the cap");
        assertEquals(Set.of(), codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, 60L, 3, null, null, TemporalSpec.CountMode.EDGES), "c1"))));
    }

    private static RuleCondition temporalPlain(String id) {
        return new RuleCondition(RuleField.VALUE, RuleOperator.GT, "1", CapabilityType.TEMPERATURE, null, null, id, null);
    }

    @Test void trendRequiresDirectionMagnitudeBoundedWindowAndAnEstablishedUnit() {
        UUID d = device();
        BigDecimal five = new BigDecimal("5");
        var good = new TemporalSpec(TemporalOperator.TREND, 40L, 300L, null, TemporalSpec.Direction.RISING, five);
        assertEquals(Set.of(), codes(rule(d, due(), temporal(good, "t"))));
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.TREND, 40L, 300L, null, null, five), "t"))).contains("INVALID_TEMPORAL"));
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.TREND, 40L, 300L, null, TemporalSpec.Direction.RISING, BigDecimal.ZERO), "t"))).contains("INVALID_TEMPORAL"));
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.TREND, 400L, 300L, null, TemporalSpec.Direction.RISING, five), "t"))).contains("INVALID_TEMPORAL"), "window must cover the span");
        assertTrue(codes(rule(d, due(), temporal(new TemporalSpec(TemporalOperator.TREND, 40L, 300L, 2, TemporalSpec.Direction.RISING, five), "t"))).contains("INVALID_TEMPORAL"), "at least three samples");
        var wrongUnit = new RuleCondition(RuleField.VALUE, RuleOperator.GT, "0", CapabilityType.TEMPERATURE, null, "FAHRENHEIT", "t", good);
        assertTrue(codes(rule(d, due(), wrongUnit)).contains("UNIT_MISMATCH"), "no implicit unit conversion");
    }

    @Test void absenceAndOfflineHaveTheirOwnReferenceRules() {
        UUID d = device();
        var absence = new TemporalSpec(TemporalOperator.ABSENCE_FOR, 60L, null, null, null, null);
        assertEquals(Set.of(), codes(rule(d, due(), new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "x", CapabilityType.TEMPERATURE, null, null, "a", absence))));
        assertTrue(codes(rule(d, due(), new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "x", CapabilityType.GAS, null, null, "a", absence))).contains("INVALID_CONDITION_CONTRACT"), "GAS is not installed on this device");
        assertTrue(codes(rule(d, due(), new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "x", null, null, null, "a", absence))).contains("INVALID_CONDITION_CONTRACT"), "binding authority is required");
        var offline = new TemporalSpec(TemporalOperator.OFFLINE_FOR, 60L, null, null, null, null);
        assertEquals(Set.of(), codes(rule(d, due(), new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "OFFLINE", null, null, null, "o", offline))));
        assertTrue(codes(rule(d, due(), new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "OFFLINE", CapabilityType.TEMPERATURE, null, null, "o", offline))).contains("INVALID_CONDITION_CONTRACT"));
        assertTrue(codes(rule(UUID.randomUUID(), due(), new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "OFFLINE", null, null, null, "o", offline))).contains("MISSING_DEVICE"));
    }
}
