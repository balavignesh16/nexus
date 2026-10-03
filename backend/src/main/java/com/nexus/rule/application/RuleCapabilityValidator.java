package com.nexus.rule.application;
import com.nexus.device.binding.runtime.RuntimeCapabilityResolver;
import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.*;
import com.nexus.device.capability.definition.validation.*;
import com.nexus.rule.domain.*;
import com.nexus.shared.exception.ValidationException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RuleCapabilityValidator {
 private final CapabilityDefinitionRepository definitions; private final RuntimeCapabilityResolver resolver; private final CapabilityValueValidator values; private final BindingConstraintService constraints;
 public RuleCapabilityValidator(CapabilityDefinitionRepository d,RuntimeCapabilityResolver r,CapabilityValueValidator v,BindingConstraintService c){definitions=d;resolver=r;values=v;constraints=c;}
 public void validate(Rule rule){
  UUID deviceId=explicitDevice(rule);
  for(var c:rule.conditions()) if(c.capabilityKey()!=null){
   CapabilityDefinition d=deviceId==null?definitions.findByKey(c.capabilityKey()).orElseThrow(()->new ValidationException("Unknown capability key: "+c.capabilityKey())):resolver.resolve(deviceId,c.capabilityKey(),CapabilityDirection.READ).definition();
   if(c.capabilityType()!=null&&!LegacyCapabilityDefinitionMapping.keyFor(c.capabilityType()).equals(d.getKey()))throw new ValidationException("capabilityType and capabilityKey conflict");
   validateOperator(c.operator(),d.getValueType()); Object expected=parse(c.expectedValue(),d.getValueType());
   var result=values.validate(d,expected,c.unit()!=null?c.unit():d.getUnit(),CapabilityOperation.TELEMETRY,null);if(!result.isValid())throw new ValidationException(result.errors().getFirst().message());
   if(deviceId!=null){var binding=resolver.resolve(deviceId,c.capabilityKey(),CapabilityDirection.READ).binding();if(binding!=null)constraints.validateValue(d,binding.getConfiguration(),expected);}
  }
  for(var a:rule.actions())if("SEND_COMMAND".equals(a.actionType())&&a.parameters()!=null&&a.parameters().get("capabilityKey")!=null){
   UUID target=UUID.fromString(String.valueOf(a.parameters().get("deviceId")));var resolved=resolver.resolve(target,String.valueOf(a.parameters().get("capabilityKey")),CapabilityDirection.WRITE);
   Object legacy=a.parameters().get("capability");if(legacy!=null&&!LegacyCapabilityDefinitionMapping.keyFor(com.nexus.device.capability.CapabilityType.valueOf(legacy.toString())).equals(resolved.definition().getKey()))throw new ValidationException("action capability and capabilityKey conflict");
   var result=values.validate(resolved.definition(),a.parameters().get("value"),resolved.effectiveUnit(),CapabilityOperation.COMMAND,String.valueOf(a.parameters().get("command")));if(!result.isValid())throw new ValidationException(result.errors().getFirst().message());
   if(resolved.binding()!=null)constraints.validateValue(resolved.definition(),resolved.binding().getConfiguration(),a.parameters().get("value"));
  }
 }
 private UUID explicitDevice(Rule r){return r.conditions().stream().filter(c->c.field()==RuleField.DEVICE_ID).map(RuleCondition::expectedValue).findFirst().map(v->{try{return UUID.fromString(v);}catch(Exception e){throw new ValidationException("Invalid DEVICE_ID");}}).orElse(null);}
 private void validateOperator(RuleOperator op,CapabilityValueType t){boolean range=op==RuleOperator.GT||op==RuleOperator.GTE||op==RuleOperator.LT||op==RuleOperator.LTE;if(t==CapabilityValueType.OBJECT||range&&t!=CapabilityValueType.NUMBER)throw new ValidationException("Operator "+op+" is invalid for "+t);}
 private Object parse(String v,CapabilityValueType t){try{return switch(t){case NUMBER->new java.math.BigDecimal(v);case BOOLEAN->{if(!"true".equalsIgnoreCase(v)&&!"false".equalsIgnoreCase(v))throw new IllegalArgumentException();yield Boolean.valueOf(v);}case STRING,ENUM->v;case OBJECT->throw new IllegalArgumentException();};}catch(Exception e){throw new ValidationException("Invalid expected value for "+t);}}
}
