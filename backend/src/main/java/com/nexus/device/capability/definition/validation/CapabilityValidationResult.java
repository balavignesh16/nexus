package com.nexus.device.capability.definition.validation;

import java.util.List;

public record CapabilityValidationResult(List<CapabilityValidationError> errors) {
    public CapabilityValidationResult {
        errors = List.copyOf(errors);
    }
    public static CapabilityValidationResult valid() { return new CapabilityValidationResult(List.of()); }
    public static CapabilityValidationResult invalid(CapabilityValidationError error) {
        return new CapabilityValidationResult(List.of(error));
    }
    public boolean isValid() { return errors.isEmpty(); }
}
