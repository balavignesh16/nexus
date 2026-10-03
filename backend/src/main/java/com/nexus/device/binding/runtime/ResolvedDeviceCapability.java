package com.nexus.device.binding.runtime;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.capability.definition.CapabilityDefinition;
import java.util.Map;
public record ResolvedDeviceCapability(CapabilityDefinition definition,DeviceCapabilityBinding binding,String effectiveUnit,
 Map<String,Object> effectiveConstraints,CapabilityResolutionSource resolutionSource,String externalIdentifier) {}
