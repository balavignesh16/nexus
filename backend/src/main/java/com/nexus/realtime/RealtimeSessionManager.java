package com.nexus.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active WebSocket STOMP sessions.
 *
 * Foundation for:
 * - Connection health monitoring.
 * - F7.5: Live Presence (multiple operators online).
 * - Future: per-user subscription management.
 */
@Component
public class RealtimeSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionManager.class);

    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    @EventListener
    public void onConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            activeSessions.put(sessionId, new SessionInfo(sessionId, Instant.now()));
            log.info("WebSocket client connected. sessionId={} totalSessions={}", sessionId, activeSessions.size());
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            activeSessions.remove(sessionId);
            log.info("WebSocket client disconnected. sessionId={} totalSessions={}", sessionId, activeSessions.size());
        }
    }

    public int getConnectedClientCount() {
        return activeSessions.size();
    }

    public Map<String, SessionInfo> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }

    /** Alias retained for the presence API described by the realtime contract. */
    public Map<String, SessionInfo> getSessions() {
        return getActiveSessions();
    }

    public record SessionInfo(String sessionId, Instant connectedAt) {}
}
