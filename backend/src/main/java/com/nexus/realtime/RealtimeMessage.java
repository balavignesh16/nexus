package com.nexus.realtime;

import java.util.UUID;

/**
 * Unified versioned envelope for all WebSocket broadcast messages.
 *
 * Every message sent from the backend carries this structure so the
 * frontend can process any event with a single typed handler.
 *
 * @param eventId         Unique identifier for this broadcast message.
 * @param eventType       Discriminator: "TelemetryReceived", "TwinUpdated", etc.
 * @param deviceId        Source device UUID (nullable for platform-level events).
 * @param correlationId   Links telemetry → twin → rule → action in a single trace.
 * @param serverTimestamp Epoch milliseconds on the backend clock. Used by the frontend
 *                        to compute a client/server clock offset for chart alignment.
 * @param payload         The event-specific data. Shape depends on eventType.
 * @param source          "BACKEND" | "REPLAY". Frontend Demo Mode uses "DEMO" locally.
 * @param version         Envelope schema version. Currently 1.
 */
public record RealtimeMessage(
        String  eventId,
        String  eventType,
        String  deviceId,
        String  correlationId,
        long    serverTimestamp,
        long    sequenceNumber,
        Object  payload,
        String  source,
        int     version
) {
    /** Convenience factory for backend-originated messages. */
    public static RealtimeMessage of(String eventType, String deviceId, String correlationId, long sequenceNumber, Object payload) {
        return new RealtimeMessage(
                UUID.randomUUID().toString(),
                eventType,
                deviceId,
                correlationId,
                System.currentTimeMillis(),
                sequenceNumber,
                payload,
                "BACKEND",
                1
        );
    }
}
