package com.nexus.site.application;

import java.util.UUID;

public class SiteNotFoundException extends com.nexus.shared.exception.ResourceNotFoundException {
    public SiteNotFoundException(UUID id) {
        super("Site", id.toString());
    }
}
