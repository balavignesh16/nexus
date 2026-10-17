package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Proves a command payload is complete against the wire contract BEFORE it is persisted. The database (V37) proves only
 * the topic, hash, size, {@code commandId} and {@code timestamp}; the firmware additionally requires {@code capability}
 * (string), {@code command} (string), {@code value} and an integer {@code timestamp}
 * (firmware/src/main.cpp: {@code is<const char*>()}, {@code is<bool>()}, {@code is<long>()}), and the simulator requires
 * all five fields to be present. A payload missing any of them would be accepted, queued, published and then rejected by
 * the device as MALFORMED, so it is refused at acceptance instead and nothing is written.
 *
 * The value TYPE is not restricted here: the supported value shapes depend on the capability and are validated by the
 * existing capability and binding validators in {@code CommandExecutor.prepare}, which run first.
 */
public final class OutboxPayloadValidator {
    private OutboxPayloadValidator() {}

    private static final Set<String> FIELDS = Set.of("commandId", "capability", "command", "value", "timestamp");
    public static final int MAX_PAYLOAD_CHARS = 4096;

    /** @throws IllegalArgumentException with a stable message when the payload is not a complete command */
    public static void validate(UUID commandId, OutboxPayload payload, ObjectMapper mapper) {
        if (payload.text().length() > MAX_PAYLOAD_CHARS) throw new IllegalArgumentException("Command payload is too large");
        JsonNode node;
        try {
            node = mapper.readTree(payload.text());
        } catch (Exception e) {
            throw new IllegalArgumentException("Command payload is not valid JSON");
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Command payload must be a JSON object");
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!FIELDS.contains(names.next())) throw new IllegalArgumentException("Command payload has an unexpected field");
        }
        if (!node.path("commandId").isTextual() || !commandId.toString().equals(node.get("commandId").asText()))
            throw new IllegalArgumentException("Command payload commandId is missing or wrong");
        if (!node.path("capability").isTextual() || node.get("capability").asText().isBlank())
            throw new IllegalArgumentException("Command payload capability is missing");
        if (!node.path("command").isTextual() || node.get("command").asText().isBlank())
            throw new IllegalArgumentException("Command payload command is missing");
        if (!node.has("value") || node.get("value").isNull() || node.get("value").isMissingNode())
            throw new IllegalArgumentException("Command payload value is missing");
        JsonNode timestamp = node.get("timestamp");
        if (timestamp == null || !timestamp.isIntegralNumber() || !timestamp.canConvertToLong() || timestamp.asLong() <= 0
                || timestamp.asLong() != payload.epochSeconds())
            throw new IllegalArgumentException("Command payload timestamp must be a positive integer equal to the acceptance time");
    }
}
