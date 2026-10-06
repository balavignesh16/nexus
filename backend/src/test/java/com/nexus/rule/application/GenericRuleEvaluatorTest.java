package com.nexus.rule.application;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.event.domain.*;
import com.nexus.rule.domain.*;
import com.nexus.twin.domain.*;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import org.junit.jupiter.api.Test;
import java.time.Instant; import java.util.*;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;

class GenericRuleEvaluatorTest {
 @Test void evaluatesGenericNumberUsingTypedTwinAndRecordsIdentity(){
  UUID device=UUID.randomUUID(),definition=UUID.randomUUID(),binding=UUID.randomUUID(); DigitalTwinRegistry registry=mock(DigitalTwinRegistry.class);
  var twin=new DigitalTwin(device,Instant.now(),"temperature",new java.math.BigDecimal("35.50"),"CELSIUS",Instant.now(),Map.of(),definition,binding,"temperature",CapabilityValueType.NUMBER,CapabilityResolutionSource.GENERIC_BINDING);
  when(registry.getAllForDevice(device)).thenReturn(List.of(twin)); var evaluator=new RuleEvaluator(registry);
  var condition=new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",null,"temperature","CELSIUS");
  var rule=new Rule(UUID.randomUUID(),"generic",true,1,List.of(condition),List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of());
  var event=new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,device,Instant.now(),"test",Map.of(),"INFO","c");
  var result=evaluator.evaluate(rule,event); assertTrue(result.matched()); assertEquals("temperature",result.evidence().getFirst().capabilityKey()); assertEquals(binding,result.evidence().getFirst().bindingId());
 }

 @Test void staleGenericStateIsEvidenceButCannotMatch(){
  UUID device=UUID.randomUUID(),definition=UUID.randomUUID(),binding=UUID.randomUUID();
  DigitalTwinRegistry registry=mock(DigitalTwinRegistry.class);
  TwinStateQualityEvaluator quality=mock(TwinStateQualityEvaluator.class);
  Instant receivedAt=Instant.parse("2026-10-06T00:00:00Z");
  var twin=new DigitalTwin(device,receivedAt,"temperature","temperature",new java.math.BigDecimal("40"),
          "CELSIUS",receivedAt,receivedAt,Map.of(),definition,binding,"temperature",
          CapabilityValueType.NUMBER,CapabilityResolutionSource.GENERIC_BINDING);
  when(registry.getAllForDevice(device)).thenReturn(List.of(twin));
  when(quality.evaluate(twin)).thenReturn(new TwinStateQualityEvidence(TwinStateQuality.STALE,
          "FRESHNESS_THRESHOLD_EXCEEDED",receivedAt.plusSeconds(121),receivedAt,120,"ONLINE"));
  var evaluator=new RuleEvaluator(registry,quality);
  var condition=new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",null,"temperature","CELSIUS");
  var rule=new Rule(UUID.randomUUID(),"generic",true,1,List.of(condition),List.of(),Map.of());
  var result=evaluator.evaluate(rule,new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,device,
          receivedAt,"test",Map.of(),"INFO","c"));
  assertFalse(result.matched());
  assertEquals("STALE",result.evidence().getFirst().stateQuality());
  assertEquals("FRESHNESS_THRESHOLD_EXCEEDED",result.evidence().getFirst().qualityReason());
 }
}
