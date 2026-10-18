package com.nexus.adapter.spi;

/** Bytes and transport metadata to a typed telemetry payload. Never throws for bad input: it returns a {@link Rejection}. */
public interface TelemetryDecoder {
    Decoded<TelemetryPayload> decodeTelemetry(RawMessage message);
}
