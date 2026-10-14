package com.nexus.rule.snapshot;

import com.nexus.device.binding.runtime.ResolvedDeviceCapability;
import java.util.UUID;

/**
 * Detached installed-contract facts a condition evaluation needs. Copied out of managed
 * entities so a later binding/definition edit cannot change an in-flight decision.
 */
public record CapturedCapability(UUID definitionId,String definitionKey,boolean unitlessContract,
        UUID bindingId,String effectiveUnit) {
    public static CapturedCapability of(ResolvedDeviceCapability resolved){
        return new CapturedCapability(resolved.definition().getId(),resolved.definition().getKey(),
            resolved.definition().getAllowedUnits().isEmpty(),
            resolved.binding()==null?null:resolved.binding().getId(),resolved.effectiveUnit());
    }
}
