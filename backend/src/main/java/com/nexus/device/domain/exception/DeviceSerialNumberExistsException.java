package com.nexus.device.domain.exception;

public class DeviceSerialNumberExistsException extends RuntimeException {
    public DeviceSerialNumberExistsException(String serialNumber) {
        super("Device with serial number already exists: " + serialNumber);
    }
}
