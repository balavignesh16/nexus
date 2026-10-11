package com.nexus.twin.application;

import com.nexus.device.capability.definition.CapabilityValueType;

/** Extensible readback extraction from a validated reported value. */
public interface ReadbackSelector {
    String key();
    boolean supports(CapabilityValueType reportedType,String path);
    Object select(Object reported,String path);
}
