package com.nexus.adapter.spi;

import java.time.Duration;

/**
 * Puts ONE already-accepted, already-leased command on the transport and classifies the outcome conservatively. An adapter without
 * this capability is ingest-only; the conformance report then says "command egress: not supported", never a pass.
 */
public interface CommandEgress {
    Attempt deliver(CommandEnvelope command, Duration timeout);
}
