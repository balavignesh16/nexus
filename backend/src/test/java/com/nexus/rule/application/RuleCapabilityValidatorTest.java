package com.nexus.rule.application;
import com.nexus.device.binding.*;
import com.nexus.device.binding.runtime.*;
import com.nexus.device.capability.*;
import com.nexus.device.capability.definition.*;
import com.nexus.device.capability.definition.validation.*;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleCapabilityValidatorTest {
    @Test void effectiveUnitsAndAmbiguousInstalledAliasesHaveConditionLocations(){
        UUID device=UUID.randomUUID();var defs=mock(CapabilityDefinitionRepository.class);var installed=mock(DeviceCapabilityRepository.class);var bindings=mock(DeviceCapabilityBindingRepository.class);var devices=mock(DeviceRepository.class);
        var definition=new CapabilityDefinition(UUID.randomUUID(),"audit_temperature","Audit",null,CapabilityDirection.READ,CapabilityValueType.NUMBER,"CELSIUS",List.of("CELSIUS","FAHRENHEIT"),null,null,List.of(),List.of(),null,Map.of(),Map.of());
        var binding=new DeviceCapabilityBinding(UUID.randomUUID(),device,definition,null,true,"CELSIUS",Map.of(),Map.of("telemetryKey","Alias"),Instant.now());when(bindings.findByDeviceId(device)).thenReturn(List.of(binding));when(devices.existsById(device)).thenReturn(true);
        var validator=new RuleCapabilityValidator(defs,new RuntimeCapabilityResolver(bindings,installed,defs),new CapabilityValueValidator(),new BindingConstraintService());validator.installAuthority(devices,installed,bindings);
        for(String unit:new String[]{"CELSIUS",null})assertTrue(validator.inspect(rule(device,"aLiAs",unit)).isEmpty());
        var error=validator.inspect(rule(device,"aLiAs","FAHRENHEIT")).getFirst();assertEquals("UNIT_MISMATCH",error.get("code"));assertEquals("conditions[1]",error.get("path"));
        var other=new DeviceCapabilityBinding(UUID.randomUUID(),device,definition,null,true,"CELSIUS",Map.of(),Map.of("telemetryKey","Alias"),Instant.now());when(bindings.findByDeviceId(device)).thenReturn(List.of(binding,other));assertFalse(validator.inspect(rule(device,"ALIAS",null)).isEmpty());
    }
    private Rule rule(UUID device,String key,String unit){return new Rule(UUID.randomUUID(),"Rule",false,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,device.toString()),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",null,key,unit)),List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of());}
}
