package com.nexus.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe bounded circular buffer for all broadcast messages.
 *
 * Every message published via RealtimePublisher is stored here before
 * broadcasting so the RealtimeReplayController can serve missed events
 * when a client reconnects.
 *
 * Memory behaviour:
 * - Each topic has its own deque bounded to MAX_PER_TOPIC entries.
 * - Oldest entries are evicted when the limit is exceeded.
 * - Total memory is bounded to (MAX_PER_TOPIC * NUM_TOPICS * avg message size).
 */
@Component
public class RealtimeBuffer {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBuffer.class);
    private final int maxPerTopic;

    private final Map<String, Deque<RealtimeMessage>> buffers = new ConcurrentHashMap<>();

    public RealtimeBuffer(@Value("${nexus.realtime.buffer-size:10000}") int maxPerTopic) {
        this.maxPerTopic = Math.max(1, maxPerTopic);
    }

    /**
     * Appends a message to the per-topic buffer.
     * Evicts the oldest entry if the buffer is at capacity.
     */
    public void append(String topic, RealtimeMessage message) {
        Deque<RealtimeMessage> deque = buffers.computeIfAbsent(topic, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(message);
        if (deque.size() > maxPerTopic) {
            deque.pollFirst(); // evict oldest
        }
    }

    /**
     * Returns all messages for a topic with serverTimestamp > since.
     * Safe to call concurrently.
     *
     * @param topic Topic name (e.g. "/topic/telemetry")
     * @param since Epoch milliseconds. Only messages after this timestamp are returned.
     * @param limit Maximum number of messages to return.
     */
    public List<RealtimeMessage> getSince(String topic, long since, int limit) {
        Deque<RealtimeMessage> deque = buffers.get(topic);
        if (deque == null) return List.of();

        List<RealtimeMessage> result = new ArrayList<>();
        for (RealtimeMessage msg : deque) {
            if (msg.serverTimestamp() > since) {
                result.add(msg);
                if (result.size() >= limit) break;
            }
        }
        log.debug("Replay: topic={}, since={}, found={}", topic, since, result.size());
        return result;
    }

    /** Returns the total number of buffered messages across all topics. */
    public int totalSize() {
        return buffers.values().stream().mapToInt(Deque::size).sum();
    }
}
