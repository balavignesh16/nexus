package com.nexus.rule.application;

import com.nexus.device.binding.*;
import com.nexus.device.binding.runtime.*;
import com.nexus.device.capability.*;
import com.nexus.device.capability.definition.*;
import com.nexus.device.capability.definition.validation.*;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.event.domain.*;
import com.nexus.rule.domain.*;
import com.nexus.twin.domain.*;
import com.nexus.twin.persistence.InMemoryDigitalTwinRegistry;
import com.nexus.twin.application.*;
import com.nexus.device.connectivity.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real resolver/evaluator/quality semantics; PostgreSQL pipeline proof lives in authorization integration. */
class RuleLegacyCapabilityEvaluatorTest {
    private final UUID device=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-13T12:00:00Z");
    private final CapabilityDefinition definition=new CapabilityDefinition(UUID.randomUUID(),"temperature","Temperature",null,
        CapabilityDirection.READ,CapabilityValueType.NUMBER,"CELSIUS",List.of("CELSIUS","FAHRENHEIT"),null,null,List.of(),List.of(),null,Map.of(),Map.of());
    private final DeviceCapabilityBinding binding=new DeviceCapabilityBinding(UUID.randomUUID(),device,definition,null,true,"CELSIUS",Map.of(),Map.of("telemetryKey","TemperatureAlias"),now);
    private final DeviceCapabilityBindingRepository bindings=mock(DeviceCapabilityBindingRepository.class);
    private final CapabilityDefinitionRepository definitions=mock(CapabilityDefinitionRepository.class);
    private final DeviceCapabilityRepository installed=mock(DeviceCapabilityRepository.class);
    private final DeviceRepository devices=mock(DeviceRepository.class);
    private final InMemoryDigitalTwinRegistry twins=new InMemoryDigitalTwinRegistry(mock(DigitalTwinRepository.class));
    private final RuleCapabilityValidator validator;
    private final RuleEvaluator evaluator;

    RuleLegacyCapabilityEvaluatorTest(){
        when(bindings.findByDeviceId(device)).thenReturn(List.of(binding));
        when(bindings.findById(binding.getId())).thenReturn(Optional.of(binding));
        when(definitions.findByKey("temperature")).thenReturn(Optional.of(definition));
        when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(devices.existsById(device)).thenReturn(true);
        when(installed.existsByDeviceIdAndCapabilityTypeAndDirection(device,CapabilityType.TEMPERATURE,CapabilityDirection.READ)).thenReturn(true);
        validator=new RuleCapabilityValidator(definitions,new RuntimeCapabilityResolver(bindings,installed,definitions),new CapabilityValueValidator(),new BindingConstraintService());
        validator.installAuthority(devices,installed,bindings);
        var connectivity=mock(DeviceConnectivityRegistry.class);
        when(connectivity.get(device)).thenReturn(Optional.of(new DeviceConnectivity(device,DeviceConnectivityStatus.ONLINE,now)));
        evaluator=new RuleEvaluator(twins,new TwinStateQualityEvaluator(connectivity,bindings,new FreshnessPolicy(),Clock.fixed(now,ZoneOffset.UTC),definitions,new CapabilityValueValidator()));
        evaluator.setReferences(validator);
    }
    private DigitalTwin state(Object value,String unit,String wire,UUID definitionId,UUID bindingId,Instant received){
        var state=new DigitalTwin(device,now,"temperature",wire,value,unit,now,received,Map.of(),definitionId,bindingId,"temperature",CapabilityValueType.NUMBER,
            bindingId==null?CapabilityResolutionSource.LEGACY_ADAPTER:CapabilityResolutionSource.GENERIC_BINDING);
        twins.project(state);return state;
    }
    private Rule rule(String unit){return new Rule(UUID.randomUUID(),"Legacy safety",true,1,
        List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,device.toString()),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",CapabilityType.TEMPERATURE,null,unit)),
        List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of());}
    private DomainEvent event(){return new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,device,now,"unit",Map.of(),"INFO","legacy-safety");}

    @Test void explicitAndOmittedLegacyUnitsRejectGoodCrossUnitObservation(){
        state(new BigDecimal("86"),"FAHRENHEIT","TEMPERATURE",definition.getId(),binding.getId(),now);
        for(String unit:Arrays.asList("CELSIUS",null)){
            var rule=rule(unit);assertTrue(validator.inspect(rule).isEmpty());
            var result=evaluator.evaluate(rule,event());assertFalse(result.matched());
            assertEquals("GOOD",result.evidence().get(1).stateQuality());
        }
    }
    @Test void canonicalAliasReadsAgreeWithGenericAndExposeActualBinding(){
        for(String wire:List.of("TemperatureAlias","tEmPeRaTuReAlIaS")){
            state(new BigDecimal("40"),"CELSIUS",wire,definition.getId(),binding.getId(),now);
            var legacy=rule(null);var generic=new Rule(legacy.ruleId(),legacy.name(),true,1,List.of(legacy.conditions().getFirst(),
                new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",null,"tEmPeRaTuReAlIaS",null)),legacy.actions(),Map.of());
            var result=evaluator.evaluate(legacy,event());assertTrue(result.matched());assertTrue(evaluator.evaluate(generic,event()).matched());
            assertEquals(binding.getId(),result.evidence().get(1).bindingId());assertEquals(definition.getId(),result.evidence().get(1).definitionId());
            assertEquals("temperature",result.evidence().get(1).capabilityKey());assertEquals("CELSIUS",result.evidence().get(1).unit());
        }
    }
    @Test void retainedWireAliasCannotReplaceCanonicalBelowThresholdState(){
        state(new BigDecimal("20"),"CELSIUS","TemperatureAlias",definition.getId(),binding.getId(),now);
        UUID otherBinding=UUID.randomUUID();
        twins.project(new DigitalTwin(device,now,"other_temperature","TEMPERATURE",new BigDecimal("99"),"CELSIUS",now,now,Map.of(),definition.getId(),otherBinding,"other_temperature",CapabilityValueType.NUMBER,CapabilityResolutionSource.GENERIC_BINDING));
        var result=evaluator.evaluate(rule(null),event());assertFalse(result.matched());
        assertEquals(20.0,result.evidence().get(1).actualValue());assertEquals(binding.getId(),result.evidence().get(1).bindingId());
        assertEquals(2,twins.getAllForDevice(device).size(),"Retained evidence is not deleted to resolve identity");
    }
    @Test void missingUnknownStaleAndWrongDefinitionProvenanceFailClosed(){
        for(String unit:Arrays.asList(null,"UNKNOWN")){
            state(new BigDecimal("40"),unit,"TEMPERATURE",definition.getId(),binding.getId(),now);
            assertFalse(evaluator.evaluate(rule(null),event()).matched());
        }
        state(new BigDecimal("40"),"CELSIUS","TEMPERATURE",definition.getId(),binding.getId(),now.minusSeconds(121));
        var stale=evaluator.evaluate(rule(null),event());assertFalse(stale.matched());assertEquals("STALE",stale.evidence().get(1).stateQuality());
        var wrongDefinition=UUID.randomUUID();when(definitions.findById(wrongDefinition)).thenReturn(Optional.of(definition));
        state(new BigDecimal("40"),"CELSIUS","TEMPERATURE",wrongDefinition,binding.getId(),now);
        assertFalse(evaluator.evaluate(rule(null),event()).matched());
    }
    @Test void ambiguousAndDisabledInstalledReferencesCannotUseLegacyFallback(){
        state(new BigDecimal("40"),"CELSIUS","TEMPERATURE",definition.getId(),binding.getId(),now);
        var duplicate=new DeviceCapabilityBinding(UUID.randomUUID(),device,definition,null,true,"CELSIUS",Map.of(),Map.of("telemetryKey","TEMPERATURE"),now);
        when(bindings.findByDeviceId(device)).thenReturn(List.of(binding,duplicate));assertFalse(validator.inspect(rule(null)).isEmpty());assertFalse(evaluator.evaluate(rule(null),event()).matched());
        binding.update(false,"CELSIUS",Map.of(),Map.of("telemetryKey","TemperatureAlias"),now);
        when(bindings.findByDeviceId(device)).thenReturn(List.of(binding));assertFalse(evaluator.evaluate(rule(null),event()).matched());
    }
    @Test void legacyUnitMismatchUsesIndexedUnitMismatchDiagnostic(){
        var errors=validator.inspect(rule("FAHRENHEIT"));assertEquals("UNIT_MISMATCH",errors.getFirst().get("code"));assertEquals("conditions[1]",errors.getFirst().get("path"));
    }
    @Test void installedUnboundLegacyUsesCanonicalIdentityAndDoesNotInferMissingUnits(){
        when(bindings.findByDeviceId(device)).thenReturn(List.of());
        when(installed.findByDeviceIdAndCapabilityTypeAndDirection(device,CapabilityType.TEMPERATURE,CapabilityDirection.READ))
            .thenReturn(Optional.of(new DeviceCapability(UUID.randomUUID(),device,CapabilityType.TEMPERATURE,CapabilityDirection.READ,"CELSIUS",Map.of())));
        state(new BigDecimal("40"),null,"TEMPERATURE_SENSOR",definition.getId(),null,now);assertFalse(evaluator.evaluate(rule(null),event()).matched());
        state(new BigDecimal("30"),"CELSIUS","TEMPERATURE_SENSOR",definition.getId(),null,now);assertFalse(evaluator.evaluate(rule(null),event()).matched());
        state(new BigDecimal("40"),"CELSIUS","TEMPERATURE_SENSOR",definition.getId(),null,now);assertTrue(evaluator.evaluate(rule(null),event()).matched());
    }
    @Test void canonicalTypedBooleanKeepsLegacyBooleanComparison(){
        var motion=new CapabilityDefinition(UUID.randomUUID(),"motion","Motion",null,CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",List.of("BOOLEAN"),null,null,List.of(),List.of(),null,Map.of(),Map.of());
        var installedMotion=new DeviceCapabilityBinding(UUID.randomUUID(),device,motion,null,true,"BOOLEAN",Map.of(),Map.of("telemetryKey","MotionAlias"),now);
        when(bindings.findByDeviceId(device)).thenReturn(List.of(installedMotion));when(bindings.findById(installedMotion.getId())).thenReturn(Optional.of(installedMotion));when(definitions.findById(motion.getId())).thenReturn(Optional.of(motion));
        twins.project(new DigitalTwin(device,now,"motion","MotionAlias",true,"BOOLEAN",now,now,Map.of(),motion.getId(),installedMotion.getId(),"motion",CapabilityValueType.BOOLEAN,CapabilityResolutionSource.GENERIC_BINDING));
        var rule=new Rule(UUID.randomUUID(),"Motion",true,1,List.of(new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"true",CapabilityType.MOTION)),List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of());
        var result=evaluator.evaluate(rule,event());assertTrue(result.matched());assertEquals(true,result.evidence().getFirst().actualValue());assertEquals(installedMotion.getId(),result.evidence().getFirst().bindingId());
    }
    @Test void nullReportedUnitRequiresAnExplicitlyUnitlessInstalledContract(){
        definition.update("Temperature",null,null,List.of("CELSIUS","FAHRENHEIT"),null,null,List.of(),List.of(),null,Map.of(),Map.of());
        binding.update(true,null,Map.of(),Map.of("telemetryKey","TemperatureAlias"),now);
        state(new BigDecimal("40"),null,"TEMPERATURE",definition.getId(),binding.getId(),now);
        assertFalse(evaluator.evaluate(rule(null),event()).matched(),"Missing effective unit cannot choose between dimensional allowed units");
        definition.update("Temperature",null,null,List.of(),null,null,List.of(),List.of(),null,Map.of(),Map.of());
        var unitless=evaluator.evaluate(rule(null),event());assertTrue(unitless.matched());assertNull(unitless.evidence().get(1).unit(),"Authoritative unitless is preserved, not labeled with an invented unit");
    }
}
