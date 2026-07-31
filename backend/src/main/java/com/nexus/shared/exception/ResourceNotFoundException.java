package com.nexus.shared.exception;

public class ResourceNotFoundException extends NexusException {
    public ResourceNotFoundException(String resource, String identifier) {
        super(String.format("%s not found with identifier: %s", resource, identifier));
    }
}
