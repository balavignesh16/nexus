package com.nexus.rule.application;

import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.binding.runtime.RuntimeCapabilityResolver;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.capability.definition.validation.CapabilityValueValidator;
import com.nexus.device.connectivity.DeviceConnectivity;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.shared.exception.ValidationException;
import com.nexus.twin.application.FreshnessPolicy;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRepository;
import com.nexus.twin.persistence.InMemoryDigitalTwinRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real installed-reference, value, quality and evaluator semantics; repositories alone are mocked. */
class RuleGenericUnitSafetyEvaluatorTest {
    @Test
    void originalR1DimensionalNullUnitsCannotMatchGoodObservation() {
        Fixture f = dimensional(null, null);
        DigitalTwin reported = f.report(new BigDecimal("40"), null);
        Rule rule = f.generic(RuleOperator.GT, "35", null);

        assertTrue(f.validator.inspect(rule).isEmpty(), "The accepted contract must reach the runtime unit gate");
        RuleEvaluationResult result = f.evaluate(rule);
        assertGood(result);
        assertFalse(result.matched(), "NULL == NULL cannot establish dimensional-unit provenance");
        assertEquals(new BigDecimal("40"), result.evidence().get(1).actualValue());
        assertNull(reported.latestUnit(), "A rejected comparison must not invent historical units");
        assertNull(result.evidence().get(1).unit());
    }

    @Test
    void establishedCelsiusRejectsMissingReportedUnitEvenWithGoodQuality() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("40"), null);
        for (String conditionUnit : Arrays.asList("CELSIUS", null)) {
            Rule rule = f.generic(RuleOperator.GT, "35", conditionUnit);
            assertTrue(f.validator.inspect(rule).isEmpty());
            RuleEvaluationResult result = f.evaluate(rule);
            assertGood(result);
            assertFalse(result.matched());
        }
    }

    @Test
    void explicitGenericConditionMismatchHasIndexedUnitMismatchDiagnostic() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        Rule rule = f.generic(RuleOperator.GT, "35", "FAHRENHEIT");
        var diagnostics = f.validator.inspect(rule);

        assertEquals(1, diagnostics.size());
        assertEquals("UNIT_MISMATCH", diagnostics.getFirst().get("code"));
        assertEquals("conditions[1]", diagnostics.getFirst().get("path"));
        assertThrows(ValidationException.class, () -> f.validator.validate(rule));
    }

    @Test
    void reportedFahrenheitCannotSatisfyExplicitOrOmittedCelsiusCondition() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("86"), "FAHRENHEIT");
        for (String conditionUnit : Arrays.asList("CELSIUS", null)) {
            Rule rule = f.generic(RuleOperator.GT, "35", conditionUnit);
            assertTrue(f.validator.inspect(rule).isEmpty());
            RuleEvaluationResult result = f.evaluate(rule);
            assertGood(result);
            assertFalse(result.matched(), "Allowed observation units are not implicit conversion authority");
            assertEquals(new BigDecimal("86"), result.evidence().get(1).actualValue());
        }
    }

    @Test
    void unresolvedDimensionalContractCannotChooseAReportedConcreteUnit() {
        Fixture f = dimensional(null, null);
        for (String reportedUnit : List.of("CELSIUS", "FAHRENHEIT")) {
            f.report(new BigDecimal("40"), reportedUnit);
            RuleEvaluationResult result = f.evaluate(f.generic(RuleOperator.GT, "35", null));
            assertGood(result);
            assertFalse(result.matched(), "A reported unit cannot select the unresolved installed unit");
        }
    }

    @Test
    void equivalentLegacyAndGenericMissingDimensionalProvenanceBothFailClosed() {
        Fixture f = dimensional(null, null);
        f.report(new BigDecimal("40"), null);
        Rule generic = f.generic(RuleOperator.GT, "35", null);
        Rule legacy = f.legacy(CapabilityType.TEMPERATURE, RuleOperator.GT, "35", null);
        assertTrue(f.validator.inspect(generic).isEmpty());
        assertTrue(f.validator.inspect(legacy).isEmpty());

        RuleEvaluationResult genericResult = f.evaluate(generic);
        RuleEvaluationResult legacyResult = f.evaluate(legacy);
        assertGood(genericResult);
        assertGood(legacyResult);
        assertFalse(genericResult.matched());
        assertFalse(legacyResult.matched());
        assertEquals(f.binding.getId(), genericResult.evidence().get(1).bindingId());
        assertEquals(f.binding.getId(), legacyResult.evidence().get(1).bindingId());
        assertEquals(f.definition.getId(), genericResult.evidence().get(1).definitionId());
        assertEquals(f.definition.getId(), legacyResult.evidence().get(1).definitionId());
    }

    @Test
    void compatibleCelsiusMatchesWithExplicitAndOmittedConditionUnitsAndLegacyParity() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("40"), "CELSIUS");
        for (String conditionUnit : Arrays.asList("CELSIUS", null)) {
            Rule generic = f.generic(RuleOperator.GT, "35", conditionUnit);
            Rule legacy = f.legacy(CapabilityType.TEMPERATURE, RuleOperator.GT, "35", conditionUnit);
            assertTrue(f.validator.inspect(generic).isEmpty());
            assertTrue(f.validator.inspect(legacy).isEmpty());
            RuleEvaluationResult result = f.evaluate(generic);
            assertGood(result);
            assertTrue(result.matched());
            assertTrue(f.evaluate(legacy).matched());
            assertEquals("CELSIUS", result.evidence().get(1).unit());
        }
    }

    @Test
    void compatibleCelsiusBelowThresholdDoesNotMatchEitherSyntax() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("30"), "CELSIUS");
        for (Rule rule : List.of(f.generic(RuleOperator.GT, "35", "CELSIUS"),
                f.legacy(CapabilityType.TEMPERATURE, RuleOperator.GT, "35", "CELSIUS"))) {
            RuleEvaluationResult result = f.evaluate(rule);
            assertGood(result);
            assertFalse(result.matched());
        }
    }

    @Test
    void definitionDefaultEstablishesEffectiveUnitWhenBindingOmitsOverride() {
        Fixture f = dimensional("CELSIUS", null);
        f.report(new BigDecimal("40"), "CELSIUS");
        assertEquals("CELSIUS", f.validator.resolveInstalled(f.device, "temperature", CapabilityDirection.READ).effectiveUnit());
        assertTrue(f.evaluate(f.generic(RuleOperator.GT, "35", null)).matched());
        assertTrue(f.evaluate(f.legacy(CapabilityType.TEMPERATURE, RuleOperator.GT, "35", null)).matched());
    }

    @Test
    void concreteBindingOverrideRatherThanDefinitionDefaultGovernsComparison() {
        Fixture f = dimensional("CELSIUS", "FAHRENHEIT");
        f.report(new BigDecimal("86"), "FAHRENHEIT");
        Rule rule = f.generic(RuleOperator.GT, "80", null);
        assertTrue(f.validator.inspect(rule).isEmpty());
        assertEquals("FAHRENHEIT", f.validator.resolveInstalled(f.device, "temperature", CapabilityDirection.READ).effectiveUnit());
        assertTrue(f.evaluate(rule).matched());
        assertFalse(f.validator.inspect(f.generic(RuleOperator.GT, "35", "CELSIUS")).isEmpty());
    }

    @Test
    void authoritativeUnitlessNumberPreservesNumericComparisonAndLegacyParity() {
        Fixture f = new Fixture("temperature", CapabilityValueType.NUMBER, null, List.of(), null, List.of());
        Rule generic = f.generic(RuleOperator.GT, "35", null);
        Rule legacy = f.legacy(CapabilityType.TEMPERATURE, RuleOperator.GT, "35", null);
        assertTrue(f.validator.inspect(generic).isEmpty());
        assertTrue(f.validator.inspect(legacy).isEmpty());
        f.report(new BigDecimal("40"), null);
        RuleEvaluationResult result = f.evaluate(generic);
        assertGood(result);
        assertTrue(result.matched());
        assertTrue(f.evaluate(legacy).matched());
        assertNull(result.evidence().get(1).unit(), "An explicitly unitless contract needs no fabricated unit");
        f.report(new BigDecimal("30"), null);
        assertFalse(f.evaluate(generic).matched());
        assertFalse(f.evaluate(legacy).matched());
    }

    @Test
    void authoritativeUnitlessBooleanPreservesTypedComparisonAndLegacyParity() {
        Fixture f = new Fixture("motion", CapabilityValueType.BOOLEAN, null, List.of(), null, List.of());
        Rule generic = f.generic(RuleOperator.EQ, "true", null);
        Rule legacy = f.legacy(CapabilityType.MOTION, RuleOperator.EQ, "true", null);
        assertTrue(f.validator.inspect(generic).isEmpty());
        assertTrue(f.validator.inspect(legacy).isEmpty());
        f.report(true, null);
        RuleEvaluationResult result = f.evaluate(generic);
        assertGood(result);
        assertTrue(result.matched());
        assertTrue(f.evaluate(legacy).matched());
        assertEquals(Boolean.TRUE, result.evidence().get(1).actualValue());
        f.report(false, null);
        assertFalse(f.evaluate(generic).matched());
        assertFalse(f.evaluate(legacy).matched());
    }

    @Test
    void authoritativeUnitlessStringPreservesEqualityAndInequality() {
        Fixture f = new Fixture("status_text", CapabilityValueType.STRING, null, List.of(), null, List.of());
        Rule equality = f.generic(RuleOperator.EQ, "ready", null);
        Rule inequality = f.generic(RuleOperator.NE, "blocked", null);
        assertTrue(f.validator.inspect(equality).isEmpty());
        assertTrue(f.validator.inspect(inequality).isEmpty());
        f.report("ready", null);
        assertGood(f.evaluate(equality));
        assertTrue(f.evaluate(equality).matched());
        assertTrue(f.evaluate(inequality).matched());
        f.report("blocked", null);
        assertFalse(f.evaluate(equality).matched());
        assertFalse(f.evaluate(inequality).matched());
    }

    @Test
    void authoritativeUnitlessEnumPreservesApprovedMemberComparison() {
        Fixture f = new Fixture("operating_mode", CapabilityValueType.ENUM, null, List.of(), null, List.of("IDLE", "RUN"));
        Rule rule = f.generic(RuleOperator.EQ, "RUN", null);
        assertTrue(f.validator.inspect(rule).isEmpty());
        f.report("RUN", null);
        assertGood(f.evaluate(rule));
        assertTrue(f.evaluate(rule).matched());
        f.report("IDLE", null);
        assertFalse(f.evaluate(rule).matched());
    }

    @Test
    void compatibleUnitsDoNotBypassStaleQuality() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("40"), "CELSIUS", f.binding.getId(), f.now.minusSeconds(121));
        RuleEvaluationResult result = f.evaluate(f.generic(RuleOperator.GT, "35", null));
        assertFalse(result.matched());
        assertEquals("STALE", result.evidence().get(1).stateQuality());
    }

    @Test
    void compatibleUnitsDoNotBypassOfflineQuality() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("40"), "CELSIUS");
        when(f.connectivity.get(f.device)).thenReturn(Optional.of(new DeviceConnectivity(f.device, DeviceConnectivityStatus.OFFLINE, f.now)));
        RuleEvaluationResult result = f.evaluate(f.generic(RuleOperator.GT, "35", null));
        assertFalse(result.matched());
        assertEquals("OFFLINE", result.evidence().get(1).stateQuality());
    }

    @Test
    void unrelatedBindingCannotSupplyComparisonEvenWithMatchingKeyAndGoodValue() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("99"), "CELSIUS", UUID.randomUUID(), f.now);
        RuleEvaluationResult missing = f.evaluate(f.generic(RuleOperator.GT, "35", null));
        assertFalse(missing.matched());
        assertEquals("MISSING", missing.evidence().get(1).actualValue());
        f.report(new BigDecimal("20"), "CELSIUS");
        RuleEvaluationResult canonical = f.evaluate(f.generic(RuleOperator.GT, "35", null));
        assertGood(canonical);
        assertFalse(canonical.matched());
        assertEquals(new BigDecimal("20"), canonical.evidence().get(1).actualValue());
        assertEquals(f.binding.getId(), canonical.evidence().get(1).bindingId());
        assertEquals(2, f.twins.getAllForDevice(f.device).size(), "Safety selection must not delete retained observations");
    }

    @Test
    void compatibleUnitsDoNotMakeInvalidPersistedValueGood() {
        Fixture f = dimensional("CELSIUS", "CELSIUS");
        f.report(new BigDecimal("40"), "CELSIUS");
        f.definition.update("Current temperature contract", null, "CELSIUS", List.of("CELSIUS", "FAHRENHEIT"),
                null, new BigDecimal("36"), List.of(), List.of(), null, Map.of(), Map.of());
        RuleEvaluationResult result = f.evaluate(f.generic(RuleOperator.GT, "35", null));
        assertFalse(result.matched());
        assertEquals("UNKNOWN", result.evidence().get(1).stateQuality());
        assertEquals("INVALID_PERSISTED_VALUE", result.evidence().get(1).qualityReason());
    }

    private static Fixture dimensional(String defaultUnit, String bindingUnit) {
        return new Fixture("temperature", CapabilityValueType.NUMBER, defaultUnit,
                List.of("CELSIUS", "FAHRENHEIT"), bindingUnit, List.of());
    }

    private static void assertGood(RuleEvaluationResult result) {
        assertEquals("GOOD", result.evidence().get(1).stateQuality(), "Unit safety must not be hidden behind a non-GOOD fixture");
    }

    private static final class Fixture {
        private final UUID device = UUID.randomUUID();
        private final Instant now = Instant.parse("2026-10-13T12:00:00Z");
        private final CapabilityDefinition definition;
        private final DeviceCapabilityBinding binding;
        private final DeviceCapabilityBindingRepository bindings = mock(DeviceCapabilityBindingRepository.class);
        private final CapabilityDefinitionRepository definitions = mock(CapabilityDefinitionRepository.class);
        private final DeviceCapabilityRepository installed = mock(DeviceCapabilityRepository.class);
        private final DeviceRepository devices = mock(DeviceRepository.class);
        private final DeviceConnectivityRegistry connectivity = mock(DeviceConnectivityRegistry.class);
        private final InMemoryDigitalTwinRegistry twins = new InMemoryDigitalTwinRegistry(mock(DigitalTwinRepository.class));
        private final RuleCapabilityValidator validator;
        private final RuleEvaluator evaluator;

        private Fixture(String key, CapabilityValueType valueType, String defaultUnit, List<String> allowedUnits,
                String bindingUnit, List<String> enumValues) {
            definition = new CapabilityDefinition(UUID.randomUUID(), key, "R1 " + key, null,
                    CapabilityDirection.READ, valueType, defaultUnit, allowedUnits, null, null,
                    enumValues, List.of(), null, Map.of(), Map.of());
            binding = new DeviceCapabilityBinding(UUID.randomUUID(), device, definition, null, true,
                    bindingUnit, Map.of(), Map.of("telemetryKey", key + "_wire"), now);
            when(bindings.findByDeviceId(device)).thenReturn(List.of(binding));
            when(bindings.findById(binding.getId())).thenReturn(Optional.of(binding));
            when(definitions.findByKey(key)).thenReturn(Optional.of(definition));
            when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));
            when(devices.existsById(device)).thenReturn(true);
            if ("temperature".equals(key)) {
                when(installed.existsByDeviceIdAndCapabilityTypeAndDirection(device, CapabilityType.TEMPERATURE, CapabilityDirection.READ)).thenReturn(true);
            } else if ("motion".equals(key)) {
                when(installed.existsByDeviceIdAndCapabilityTypeAndDirection(device, CapabilityType.MOTION, CapabilityDirection.READ)).thenReturn(true);
            }
            validator = new RuleCapabilityValidator(definitions, new RuntimeCapabilityResolver(bindings, installed, definitions),
                    new CapabilityValueValidator(), new BindingConstraintService());
            validator.installAuthority(devices, installed, bindings);
            when(connectivity.get(device)).thenReturn(Optional.of(new DeviceConnectivity(device, DeviceConnectivityStatus.ONLINE, now)));
            evaluator = new RuleEvaluator(twins, new TwinStateQualityEvaluator(connectivity, bindings,
                    new FreshnessPolicy(), Clock.fixed(now, ZoneOffset.UTC), definitions, new CapabilityValueValidator()));
            evaluator.setReferences(validator);
        }

        private DigitalTwin report(Object value, String unit) {
            return report(value, unit, binding.getId(), now);
        }

        private DigitalTwin report(Object value, String unit, UUID reportedBindingId, Instant receivedAt) {
            DigitalTwin reported = new DigitalTwin(device, now, definition.getKey(), definition.getKey() + "_wire",
                    value, unit, now, receivedAt, Map.of(), definition.getId(), reportedBindingId,
                    definition.getKey(), definition.getValueType(), CapabilityResolutionSource.GENERIC_BINDING);
            twins.project(reported);
            return reported;
        }

        private Rule generic(RuleOperator operator, String expected, String unit) {
            return rule(new RuleCondition(RuleField.VALUE, operator, expected, null, definition.getKey(), unit));
        }

        private Rule legacy(CapabilityType type, RuleOperator operator, String expected, String unit) {
            return rule(new RuleCondition(RuleField.VALUE, operator, expected, type, null, unit));
        }

        private Rule rule(RuleCondition valueCondition) {
            return new Rule(UUID.randomUUID(), "R1 unit provenance", true, 1,
                    List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), valueCondition),
                    List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        }

        private RuleEvaluationResult evaluate(Rule rule) {
            DomainEvent event = new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, device, now,
                    "R1", Map.of(), "INFO", "generic-unit-safety");
            return evaluator.evaluate(rule, event);
        }
    }
}
