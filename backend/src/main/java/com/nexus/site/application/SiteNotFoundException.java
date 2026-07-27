package com.nexus.site.application;

import java.util.UUID;

public class SiteNotFoundException extends RuntimeException {
    public SiteNotFoundException(UUID id) {
        super("Site not found with ID: " + id);
    }
}
