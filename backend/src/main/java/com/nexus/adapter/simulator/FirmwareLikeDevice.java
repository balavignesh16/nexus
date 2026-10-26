package com.nexus.adapter.simulator;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.CommandEnvelope;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * An in-process reference device that applies the committed firmware's command rules: one LED, the command SET_STATE with a JSON boolean, an
 * integer timestamp, and the ABSOLUTE 30-second freshness window judged on a whole-second clock (decision D4). REJECTED reasons are the
 * firmware's MALFORMED, UNSUPPORTED and STALE. The clock is injected. A repeated commandId is answered with the first outcome and applied once.
 */
public final class FirmwareLikeDevice implements SimulatedDevice {
    public static final long WINDOW_SECONDS = 30;
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final UUID deviceId;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean ledOn = new AtomicBoolean(false);
    private final Map<String, AckPayload.Status> outcomes = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();
    private final ObjectMapper mapper = JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public FirmwareLikeDevice(UUID deviceId, Clock clock) {
        this.deviceId = deviceId;
        this.clock = clock;
    }

    public void stop() {
        running.set(false);
    }

    public void start() {
        running.set(true);
    }

    public boolean ledOn() {
        return ledOn.get();
    }

    @Override public UUID deviceId() { return deviceId; }
    @Override public boolean running() { return running.get(); }

    @Override
    public synchronized Optional<AckPayload> onCommand(CommandEnvelope command) {
        JsonNode node;
        try {
            node = mapper.readTree(command.payload());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject() || !node.hasNonNull("commandId") || !node.get("commandId").isTextual()
                || !UUID_TEXT.matcher(node.get("commandId").asText()).matches()) {
            return Optional.empty();
        }
        String commandId = node.get("commandId").asText().toLowerCase();
        Instant now = Instant.ofEpochSecond(clock.instant().getEpochSecond());
        if (outcomes.containsKey(commandId)) return Optional.of(ack(commandId, outcomes.get(commandId), reasons.get(commandId), now));
        if (!node.hasNonNull("capability") || !node.get("capability").isTextual() || !node.hasNonNull("command") || !node.get("command").isTextual()
                || !node.has("value") || node.get("value").isNull() || !node.has("timestamp") || !node.get("timestamp").isIntegralNumber()
                || !node.get("timestamp").canConvertToLong() || !node.get("value").isBoolean()) {
            return Optional.of(remember(commandId, AckPayload.Status.REJECTED, "MALFORMED", now));
        }
        if (!"LED".equals(node.get("capability").asText()) || !"SET_STATE".equals(node.get("command").asText())) {
            return Optional.of(remember(commandId, AckPayload.Status.REJECTED, "UNSUPPORTED", now));
        }
        long age = Math.abs(now.getEpochSecond() - node.get("timestamp").asLong());
        if (age > WINDOW_SECONDS) return Optional.of(remember(commandId, AckPayload.Status.REJECTED, "STALE", now));
        ledOn.set(node.get("value").asBoolean());
        return Optional.of(remember(commandId, AckPayload.Status.ACCEPTED, null, now));
    }

    private AckPayload remember(String commandId, AckPayload.Status status, String reason, Instant now) {
        outcomes.put(commandId, status);
        if (reason != null) reasons.put(commandId, reason);
        if (outcomes.size() > 500) {
            String eldest = outcomes.keySet().iterator().next();
            outcomes.remove(eldest);
            reasons.remove(eldest);
        }
        return ack(commandId, status, reason, now);
    }

    private static AckPayload ack(String commandId, AckPayload.Status status, String reason, Instant now) {
        return new AckPayload(UUID.fromString(commandId), status, now, reason);
    }
}
