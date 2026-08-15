package com.nexus.realtime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoint for replaying missed events after a WebSocket reconnect.
 *
 * When the frontend reconnects, it calls:
 *   GET /api/realtime/replay?since=<epochMs>&topic=<topic>&limit=<n>
 *
 * The response is an ordered list of RealtimeMessage objects that the
 * frontend injects into its local buffer to fill any gaps.
 */
@RestController
@RequestMapping("/api/realtime")
public class RealtimeReplayController {

    private final RealtimeBuffer buffer;

    public RealtimeReplayController(RealtimeBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Returns missed events for a given topic after a given timestamp.
     *
     * @param since Epoch milliseconds. Only messages after this are returned.
     * @param topic Topic name (e.g. "/topic/telemetry"). Defaults to telemetry.
     * @param limit Maximum messages to return. Defaults to 200.
     */
    @GetMapping("/replay")
    public ResponseEntity<List<RealtimeMessage>> replay(
            @RequestParam long since,
            @RequestParam(defaultValue = "/topic/telemetry") String topic,
            @RequestParam(defaultValue = "200") int limit
    ) {
        List<RealtimeMessage> messages = buffer.getSince(topic, since, limit);
        return ResponseEntity.ok(messages);
    }

    /**
     * Returns diagnostics: buffer size, connected clients.
     */
    @GetMapping("/status")
    public ResponseEntity<?> status(RealtimeSessionManager sessionManager) {
        return ResponseEntity.ok(java.util.Map.of(
                "bufferedMessages", buffer.totalSize(),
                "connectedClients", sessionManager.getConnectedClientCount()
        ));
    }
}
