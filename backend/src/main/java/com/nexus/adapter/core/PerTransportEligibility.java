package com.nexus.adapter.core;

import com.nexus.adapter.spi.TransportEligibility;
import com.nexus.adapter.spi.TransportKind;

/** The eligibility rule of ONE transport; {@link TransportEligibilityRouter} picks the rule by the device's server-side assignment. */
public interface PerTransportEligibility extends TransportEligibility {
    TransportKind transport();
}
