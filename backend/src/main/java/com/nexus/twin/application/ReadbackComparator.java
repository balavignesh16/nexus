package com.nexus.twin.application;

import com.nexus.device.capability.definition.CapabilityValueType;
import java.math.BigDecimal;

/** Extensible typed comparison; implementations must be deterministic. */
public interface ReadbackComparator {
    String key();
    boolean supports(CapabilityValueType desiredType);
    boolean matches(CapabilityValueType type,Object desired,Object reported,BigDecimal tolerance);
}
