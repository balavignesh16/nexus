package com.nexus.adapter.core;

import com.nexus.adapter.http.HttpAdapterSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Decision D9: has the device made a VALID authenticated contact recently, measured on the database clock? A contact is recorded only by a
 * request that passed every check (credential, device, transport, size, content type, strict decoding); a missing, invalid, revoked or retired
 * credential, a malformed or oversized request and an inactive device never move the instant. This is an eligibility SIGNAL, not connectivity:
 * it is never written to the connectivity registry and fires no connectivity trigger.
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.http.enabled", havingValue = "true")
public class HttpContactWindow {
    private final JdbcTemplate jdbc;
    private final Duration window;

    @Autowired
    public HttpContactWindow(JdbcTemplate jdbc, HttpAdapterSettings settings) {
        this(jdbc, settings.contactEligibilityWindow());
    }

    HttpContactWindow(JdbcTemplate jdbc, Duration window) {
        this.jdbc = jdbc;
        this.window = window;
    }

    public boolean recentContact(UUID deviceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = ? AND status IN ('ACTIVE', 'RETIRING') "
                        + "AND (status = 'ACTIVE' OR retire_at > now()) AND last_valid_contact_at > now() - make_interval(secs => ?)",
                Integer.class, deviceId, (double) window.toMillis() / 1000.0);
        return count != null && count > 0;
    }

    public Duration window() {
        return window;
    }
}
