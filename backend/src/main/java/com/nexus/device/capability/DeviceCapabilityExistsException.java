package com.nexus.device.capability;

import java.util.UUID;

public class DeviceCapabilityExistsException extends RuntimeException {
    public DeviceCapabilityExistsException(UUID deviceId, CapabilityType capabilityType, CapabilityDirection direction) {
        super("Device " + deviceId + " already has a " + capabilityType + " " + direction + " capability");
    }
}
