package com.nexus.space.domain.exception;

import java.util.UUID;

public class SpaceHasDevicesException extends RuntimeException {
    public SpaceHasDevicesException(UUID spaceId) {
        super("Cannot delete space with ID " + spaceId + " because it contains devices");
    }
}
