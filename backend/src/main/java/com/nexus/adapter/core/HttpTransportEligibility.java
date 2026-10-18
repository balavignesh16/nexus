package com.nexus.adapter.core;

import com.nexus.adapter.spi.Eligibility;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The eligibility rule of the HTTP transport (decision D9): a device is eligible when it has made a valid authenticated contact within the
 * window. Command DELIVERY over HTTP is not implemented in this release (the HTTP adapter declares no command egress and the pull design is
 * deferred behind its safety proof), so the rule fails closed: no command is accepted for an HTTP device, because an accepted command could
 * only be delivered by the MQTT dispatcher, which would be the wrong transport. The window logic is built and tested so that enabling delivery
 * later changes one constructor argument, not the semantics.
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.http.enabled", havingValue = "true")
public class HttpTransportEligibility implements PerTransportEligibility {
    public static final String EGRESS_NOT_SUPPORTED = "EGRESS_NOT_SUPPORTED";
    public static final String NOT_SUPPORTED_MESSAGE = "Command delivery over HTTP is not available: the HTTP adapter is ingest-only";
    public static final String NO_RECENT_CONTACT = "NO_RECENT_CONTACT";

    private final HttpContactWindow contacts;
    private final boolean deliverySupported;

    @Autowired
    public HttpTransportEligibility(HttpContactWindow contacts) {
        this(contacts, false);
    }

    HttpTransportEligibility(HttpContactWindow contacts, boolean deliverySupported) {
        this.contacts = contacts;
        this.deliverySupported = deliverySupported;
    }

    @Override
    public TransportKind transport() {
        return TransportKind.HTTP_WEBHOOK;
    }

    @Override
    public Eligibility eligible(UUID deviceId, Instant at) {
        if (!deliverySupported) return Eligibility.no(EGRESS_NOT_SUPPORTED, NOT_SUPPORTED_MESSAGE);
        if (!contacts.recentContact(deviceId)) return Eligibility.no(NO_RECENT_CONTACT, "The device has not contacted NEXUS recently");
        return Eligibility.yes();
    }
}
