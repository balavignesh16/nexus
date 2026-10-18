package com.nexus.adapter.spi;

/** Bytes and transport metadata to a typed acknowledgement. Never throws for bad input: it returns a {@link Rejection}. */
public interface AcknowledgementDecoder {
    Decoded<AckPayload> decode(RawMessage message);
}
