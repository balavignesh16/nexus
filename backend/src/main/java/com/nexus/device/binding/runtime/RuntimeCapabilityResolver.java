package com.nexus.device.binding.runtime;

import com.nexus.device.binding.*;
import com.nexus.device.capability.*;
import com.nexus.device.capability.definition.*;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.slf4j.*; import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RuntimeCapabilityResolver {
 private static final Logger log=LoggerFactory.getLogger(RuntimeCapabilityResolver.class);
 private final DeviceCapabilityBindingRepository bindings; private final DeviceCapabilityRepository legacy;
 private final CapabilityDefinitionRepository definitions;
 public RuntimeCapabilityResolver(DeviceCapabilityBindingRepository b,DeviceCapabilityRepository l,CapabilityDefinitionRepository d){bindings=b;legacy=l;definitions=d;}
 public ResolvedDeviceCapability resolve(UUID deviceId,String identifier,CapabilityDirection direction){
  if(identifier==null||identifier.isBlank())throw new IllegalArgumentException("Capability identifier is required");
  String raw=identifier.trim(); String canonical=raw.toLowerCase(Locale.ROOT);
  List<DeviceCapabilityBinding> matches=bindings.findByDeviceId(deviceId).stream().filter(DeviceCapabilityBinding::isEnabled)
   .filter(b->matches(b,raw,canonical,direction)).toList();
  if(matches.size()>1)throw new IllegalStateException("Ambiguous external capability identifier: "+raw);
  if(matches.size()==1){var b=matches.get(0);log.debug("Resolved {} for {} using GENERIC_BINDING",raw,deviceId);return new ResolvedDeviceCapability(b.getDefinition(),b,b.getUnit()!=null?b.getUnit():b.getDefinition().getUnit(),b.getConfiguration(),CapabilityResolutionSource.GENERIC_BINDING,raw);}
  CapabilityType type; try{type=legacyType(raw);}catch(IllegalArgumentException e){throw new ResourceNotFoundException("DeviceCapabilityBinding",deviceId+":"+raw);}
  var cap=legacy.findByDeviceIdAndCapabilityTypeAndDirection(deviceId,type,direction).orElse(null);
  var definition=definitions.findByKey(LegacyCapabilityDefinitionMapping.keyFor(type)).orElseThrow(()->new IllegalStateException("Missing legacy capability definition: "+type));
  log.debug("Resolved {} for {} using LEGACY_ADAPTER",raw,deviceId);
  return new ResolvedDeviceCapability(definition,null,cap!=null&&cap.getUnit()!=null?cap.getUnit():definition.getUnit(),cap==null||cap.getMetadata()==null?Map.of():cap.getMetadata(),CapabilityResolutionSource.LEGACY_ADAPTER,raw);
 }
 private CapabilityType legacyType(String raw){
  String upper=raw.toUpperCase(Locale.ROOT);
  return switch(upper){case "TEMPERATURE_SENSOR"->CapabilityType.TEMPERATURE;case "HUMIDITY_SENSOR"->CapabilityType.HUMIDITY;
   case "PRESSURE_SENSOR"->CapabilityType.PRESSURE;case "GAS_SENSOR"->CapabilityType.GAS;case "MOTION_SENSOR"->CapabilityType.MOTION;
   case "BUTTON_SENSOR"->CapabilityType.BUTTON;default->CapabilityType.valueOf(upper);};
 }
 private boolean matches(DeviceCapabilityBinding b,String raw,String canonical,CapabilityDirection direction){if(b.getDefinition().getDirection()!=direction)return false;if(b.getDefinition().getKey().equals(canonical))return true;return b.getExternalIdentifiers().values().stream().filter(Objects::nonNull).map(Object::toString).anyMatch(v->v.equalsIgnoreCase(raw));}
}
