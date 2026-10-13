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
 private com.nexus.device.domain.DeviceRepository devices;
 private com.nexus.device.capability.DeviceCapabilityRepository installed;
 private com.nexus.device.binding.DeviceCapabilityBindingRepository bindings;
 @org.springframework.beans.factory.annotation.Autowired
 void installAuthority(com.nexus.device.domain.DeviceRepository d,com.nexus.device.capability.DeviceCapabilityRepository i,com.nexus.device.binding.DeviceCapabilityBindingRepository b){devices=d;installed=i;bindings=b;}
 private final CapabilityDefinitionRepository definitions; private final RuntimeCapabilityResolver resolver; private final CapabilityValueValidator values; private final BindingConstraintService constraints;
 public RuleCapabilityValidator(CapabilityDefinitionRepository d,RuntimeCapabilityResolver r,CapabilityValueValidator v,BindingConstraintService c){definitions=d;resolver=r;values=v;constraints=c;}
 public void validate(Rule rule){
  var diagnostics=inspectStructure(rule);
  if(!diagnostics.isEmpty())throw new ValidationException(diagnostics.getFirst().get("message").toString());
 }
 public List<Map<String,Object>> inspect(Rule rule){
  var errors=inspectStructure(rule);
  return List.copyOf(errors);
 }
 private Map<String,Object> error(String code,String message,String path){return Map.of("code",code,"message",message==null?"Invalid contract":message,"path",path);}
 private void infrastructure(RuntimeException e){if(e instanceof org.springframework.dao.DataAccessException||e instanceof jakarta.persistence.PersistenceException)throw e;}
 private List<Map<String,Object>> inspectStructure(Rule rule){
  var errors=new ArrayList<Map<String,Object>>();
  if(rule.ruleId()==null||rule.name()==null||rule.name().isBlank())errors.add(error("INVALID_RULE","Rule ID and name are required","name"));
  if(rule.conditions()==null||rule.conditions().isEmpty())errors.add(error("MISSING_CONDITIONS","At least one condition is required","conditions"));
  if(rule.actions()==null||rule.actions().isEmpty())errors.add(error("MISSING_ACTIONS","At least one action is required","actions"));
  if(rule.cooldownSeconds()!=null&&rule.cooldownSeconds()<=0)errors.add(error("INVALID_COOLDOWN","Cooldown must be positive","cooldownSeconds"));
  if(!errors.isEmpty())return errors;
  UUID scope=null; int scopeIndex=-1;
  for(int n=0;n<rule.conditions().size();n++){
   var c=rule.conditions().get(n);String path="conditions["+n+"]";
   if(c==null||c.field()==null||c.operator()==null||c.expectedValue()==null){errors.add(error("INVALID_CONDITION","Condition field/operator/value are required",path));continue;}
   if(c.field()==RuleField.DEVICE_ID){
    try{UUID next=UUID.fromString(c.expectedValue());if(c.operator()!=RuleOperator.EQ||scope!=null&&!scope.equals(next))errors.add(error("AMBIGUOUS_SCOPE","DEVICE_ID scope must use unambiguous EQ",path));scope=next;scopeIndex=n;}
    catch(Exception e){errors.add(error("AMBIGUOUS_SCOPE","Invalid DEVICE_ID scope",path));}
   }
  }
  if(scope!=null&&devices!=null&&!devices.existsById(scope))errors.add(error("MISSING_DEVICE","Scope device does not exist","conditions["+scopeIndex+"]"));
  for(int n=0;n<rule.conditions().size();n++){
   var c=rule.conditions().get(n); if(c==null||c.field()==null||c.operator()==null||c.expectedValue()==null)continue;
   String path="conditions["+n+"]";
   try{
    if(c.field()!=RuleField.VALUE&&(c.capabilityKey()!=null||c.capabilityType()!=null))throw new IllegalArgumentException("Capability references apply only to VALUE; scope/payload predicates cannot be replaced by capability state");
    if(c.field()==RuleField.EVENT_TYPE&&!Set.of("TWIN_CREATED","TWIN_UPDATED","SENSOR_VALUE_CHANGED").contains(c.expectedValue()))throw new IllegalArgumentException("Unsupported foundation legacy event configuration");
    if(c.capabilityType()==null&&c.capabilityKey()==null&&(c.operator()==RuleOperator.GT||c.operator()==RuleOperator.GTE||c.operator()==RuleOperator.LT||c.operator()==RuleOperator.LTE)){
     if(c.field()!=RuleField.VALUE||!Double.isFinite(Double.parseDouble(c.expectedValue())))throw new IllegalArgumentException("Range operator requires finite numeric VALUE");
    }
    if(c.capabilityType()!=null){
     var legacyResolved=scope==null?null:resolveInstalled(scope,LegacyCapabilityDefinitionMapping.keyFor(c.capabilityType()),CapabilityDirection.READ);
     if(legacyResolved!=null&&c.unit()!=null&&!Objects.equals(c.unit(),legacyResolved.effectiveUnit()))throw new UnitMismatch("Legacy condition unit "+c.unit()+" differs from installed effective unit "+legacyResolved.effectiveUnit()+"; no implicit conversion");
     if(scope!=null&&installed!=null&&!installed.existsByDeviceIdAndCapabilityTypeAndDirection(scope,c.capabilityType(),CapabilityDirection.READ))throw new IllegalArgumentException("Legacy READ capability is not installed");
     var kind=CapabilityValueKind.of(c.capabilityType());
     if(kind==CapabilityValueKind.BOOLEAN){validateOperator(c.operator(),CapabilityValueType.BOOLEAN);parse(c.expectedValue(),CapabilityValueType.BOOLEAN);}
     else {double value=Double.parseDouble(c.expectedValue());if(!Double.isFinite(value))throw new IllegalArgumentException("Expected number must be finite");}
     if(legacyResolved!=null){var resolved=legacyResolved;
      var result=values.validate(resolved.definition(),parse(c.expectedValue(),resolved.definition().getValueType()),c.unit()!=null?c.unit():resolved.effectiveUnit(),CapabilityOperation.TELEMETRY,null);
      if(!result.isValid())throw new IllegalArgumentException(result.errors().getFirst().message());
      if(resolved.binding()!=null)constraints.validateValue(resolved.definition(),resolved.binding().getConfiguration(),parse(c.expectedValue(),resolved.definition().getValueType()));}
    }
    if(c.capabilityKey()!=null){
     var resolved=scope==null?null:resolveInstalled(scope,c.capabilityKey(),CapabilityDirection.READ);
     var d=resolved==null?definitions.findByKey(c.capabilityKey()).orElseThrow(()->new ValidationException("Unknown capability key: "+c.capabilityKey())):resolved.definition();
     if(c.capabilityType()!=null&&!LegacyCapabilityDefinitionMapping.keyFor(c.capabilityType()).equals(d.getKey()))throw new ValidationException("capabilityType and capabilityKey conflict");
     String effective=resolved==null?d.getUnit():resolved.effectiveUnit();
     if(resolved!=null&&c.unit()!=null&&!Objects.equals(c.unit(),effective))throw new UnitMismatch("Condition unit "+c.unit()+" differs from installed effective unit "+effective+"; no implicit conversion");
     validateOperator(c.operator(),d.getValueType());Object expected=parse(c.expectedValue(),d.getValueType());
     var result=values.validate(d,expected,c.unit()!=null?c.unit():effective,CapabilityOperation.TELEMETRY,null);
     if(!result.isValid())throw new ValidationException(result.errors().getFirst().message());
     if(resolved!=null&&resolved.binding()!=null)constraints.validateValue(d,resolved.effectiveConstraints(),expected);
    }
   }catch(RuntimeException e){infrastructure(e);errors.add(error(e instanceof UnitMismatch?"UNIT_MISMATCH":"INVALID_CONDITION_CONTRACT",e.getMessage(),path));}
  }
  for(int n=0;n<rule.actions().size();n++){
   var a=rule.actions().get(n);String path="actions["+n+"]";
   try{
    if(a==null||!Set.of("SEND_COMMAND","CREATE_ALERT","WEBHOOK").contains(a.actionType()))throw new IllegalArgumentException("Unsupported action type");
    if("SEND_COMMAND".equals(a.actionType())){
     var p=a.parameters();UUID target=UUID.fromString(String.valueOf(p.get("deviceId")));
     if(devices!=null&&!devices.existsById(target))throw new IllegalArgumentException("Target device does not exist");
     if(p.get("capabilityKey")!=null){
      var resolved=resolveInstalled(target,p.get("capabilityKey").toString(),CapabilityDirection.WRITE);
      Object legacy=p.get("capability");if(legacy!=null&&!LegacyCapabilityDefinitionMapping.keyFor(com.nexus.device.capability.CapabilityType.valueOf(legacy.toString())).equals(resolved.definition().getKey()))throw new ValidationException("action capability and capabilityKey conflict");
      var result=values.validate(resolved.definition(),p.get("value"),resolved.effectiveUnit(),CapabilityOperation.COMMAND,String.valueOf(p.get("command")));
      if(!result.isValid())throw new ValidationException(result.errors().getFirst().message());
      if(resolved.binding()!=null)constraints.validateValue(resolved.definition(),resolved.effectiveConstraints(),p.get("value"));
      com.nexus.action.application.executor.CommandValueValidator.validateRelayBinding(resolved,p.get("value"));
     }
     else {
      var type=com.nexus.device.capability.CapabilityType.valueOf(String.valueOf(p.get("capability")));
      requireInstalled(target,LegacyCapabilityDefinitionMapping.keyFor(type),CapabilityDirection.WRITE);
      if(installed!=null){var cap=installed.findByDeviceIdAndCapabilityTypeAndDirection(target,type,CapabilityDirection.WRITE).orElseThrow(()->new IllegalArgumentException("Legacy WRITE capability is not installed"));
       com.nexus.action.application.executor.CommandValueValidator.validate(type,cap,(String)p.get("command"),p.get("value"));}
      var resolved=resolver.resolve(target,LegacyCapabilityDefinitionMapping.keyFor(type),CapabilityDirection.WRITE);
      var result=values.validate(resolved.definition(),p.get("value"),resolved.effectiveUnit(),CapabilityOperation.COMMAND,String.valueOf(p.get("command")));
      if(!result.isValid())throw new IllegalArgumentException(result.errors().getFirst().message());
      if(resolved.binding()!=null)constraints.validateValue(resolved.definition(),resolved.binding().getConfiguration(),p.get("value"));
      com.nexus.action.application.executor.CommandValueValidator.validateRelayBinding(resolved,p.get("value"));
     }
    }
   }catch(RuntimeException e){infrastructure(e);errors.add(error("INVALID_ACTION_CONTRACT",e.getMessage(),path));}
  }
  return errors;
 }
 private void requireInstalled(UUID device,String key,CapabilityDirection direction){
  resolveInstalled(device,key,direction);
 }
 /** One installed-reference authority shared by validation, evaluation and mutation protection. */
 private List<com.nexus.device.binding.DeviceCapabilityBinding> matchingBindings(UUID device,String identifier){
  return bindings.findByDeviceId(device).stream().filter(b->b.getDefinition().getKey().equalsIgnoreCase(identifier)||b.getExternalIdentifiers().values().stream().anyMatch(v->v!=null&&v.toString().equalsIgnoreCase(identifier))).toList();
 }
 /** Dependency identity is not execution eligibility: disabled references still need protection,
  * while a TRIGGER_DEVICE rule must not block unrelated contracts on an uninstalled device. */
 public Optional<CapabilityDefinition> referenceDefinition(UUID device,String key,CapabilityDirection direction){
  if(key==null||key.isBlank())return Optional.empty();String identifier=key.trim();
  if(bindings!=null){var matches=matchingBindings(device,identifier);if(matches.size()>1)throw new IllegalArgumentException("Ambiguous installed reference: "+identifier);if(matches.size()==1)return Optional.of(matches.getFirst().getDefinition());}
  var canonical=definitions.findByKey(identifier.toLowerCase(Locale.ROOT));if(canonical.isPresent())return canonical;
  try{return Optional.of(resolver.resolve(device,identifier,direction).definition());}
  catch(com.nexus.shared.exception.ResourceNotFoundException|IllegalArgumentException absent){return Optional.empty();}
 }
 public com.nexus.device.binding.runtime.ResolvedDeviceCapability resolveInstalled(UUID device,String key,CapabilityDirection direction){
  if(key==null||key.isBlank())throw new IllegalArgumentException("Capability identifier is required");
  String identifier=key.trim();
  if(bindings==null)return resolver.resolve(device,identifier,direction);
  var matches=matchingBindings(device,identifier);
  if(!matches.isEmpty()){
   if(matches.size()!=1||!matches.getFirst().isEnabled()||matches.getFirst().getDefinition().getDirection()!=direction)throw new IllegalArgumentException("Binding is disabled, ambiguous or has wrong direction");
  }else {
   var resolved=resolver.resolve(device,identifier,direction);
   var legacy=LegacyCapabilityDefinitionMapping.all().entrySet().stream().filter(e->e.getValue().equals(resolved.definition().getKey())).map(Map.Entry::getKey).findFirst().orElse(null);
   if(legacy==null||installed==null||!installed.existsByDeviceIdAndCapabilityTypeAndDirection(device,legacy,direction))throw new IllegalArgumentException("Capability definition is not installed on device");
  }
  return resolver.resolve(device,identifier,direction);
 }
 private static class UnitMismatch extends IllegalArgumentException {UnitMismatch(String message){super(message);}}
 private UUID explicitDevice(Rule r){return r.conditions().stream().filter(c->c.field()==RuleField.DEVICE_ID).map(RuleCondition::expectedValue).findFirst().map(v->{try{return UUID.fromString(v);}catch(Exception e){throw new ValidationException("Invalid DEVICE_ID");}}).orElse(null);}
 private void validateOperator(RuleOperator op,CapabilityValueType t){boolean range=op==RuleOperator.GT||op==RuleOperator.GTE||op==RuleOperator.LT||op==RuleOperator.LTE;if(t==CapabilityValueType.OBJECT||range&&t!=CapabilityValueType.NUMBER)throw new ValidationException("Operator "+op+" is invalid for "+t);}
 private Object parse(String v,CapabilityValueType t){try{return switch(t){case NUMBER->new java.math.BigDecimal(v);case BOOLEAN->{if(!"true".equalsIgnoreCase(v)&&!"false".equalsIgnoreCase(v))throw new IllegalArgumentException();yield Boolean.valueOf(v);}case STRING,ENUM->v;case OBJECT->throw new IllegalArgumentException();};}catch(Exception e){throw new ValidationException("Invalid expected value for "+t);}}
}
