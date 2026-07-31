package com.nexus.space.application;

import java.util.UUID;

public class SpaceNotFoundException extends com.nexus.shared.exception.ResourceNotFoundException {
    public SpaceNotFoundException(UUID id) {
        super("Space", id.toString());
    }
}
