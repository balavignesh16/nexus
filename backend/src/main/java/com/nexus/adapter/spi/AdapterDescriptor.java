package com.nexus.adapter.spi;

import java.util.Map;
import java.util.Set;

/**
 * Mandatory for every adapter: identity, the contract majors it understands, the capabilities it really has, and a read-only
 * counter snapshot. {@link #diagnostics()} never contains credential material, payload text or a device or command identifier.
 */
public interface AdapterDescriptor {
    /** Stable, lower-case identifier used as the {@code adapter} metric tag. */
    String id();

    /** The transport this adapter serves; a device is routed to the adapter whose transport equals its assignment. */
    TransportKind transport();

    Set<Integer> supportedContractMajors();

    Set<AdapterCapability> capabilities();

    /** Counters only (names to non-negative values). */
    Map<String, Long> diagnostics();
}
