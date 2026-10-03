package com.nexus.telemetry.application;
import com.nexus.device.binding.runtime.ResolvedDeviceCapability;
import java.time.Instant; import java.util.UUID;
public record NormalizedTelemetry(UUID deviceId,Instant timestamp,String capabilityKey,String externalIdentifier,Object value,
 String unit,ResolvedDeviceCapability resolved) {}
