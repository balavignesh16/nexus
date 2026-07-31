package com.nexus.device.domain.exception;

import java.util.UUID;

public class DeviceNotFoundException extends com.nexus.shared.exception.ResourceNotFoundException {
    public DeviceNotFoundException(UUID id) {
        super("Device", id.toString());
    }
}
