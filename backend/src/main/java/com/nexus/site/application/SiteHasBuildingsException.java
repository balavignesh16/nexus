package com.nexus.site.application;

import java.util.UUID;

public class SiteHasBuildingsException extends RuntimeException {
    public SiteHasBuildingsException(UUID id) {
        super("Cannot delete Site because it contains Buildings. Site ID: " + id);
    }
}
