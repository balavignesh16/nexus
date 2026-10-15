package com.nexus.action.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The exact command bytes published on every delivery attempt (contract R11): built ONCE, stored as text with the
 * SHA-256 of its UTF-8 bytes and the epoch-second timestamp it contains, and never rebuilt or refreshed. The field
 * order and names are those of CommandExecutor.buildPayload so a device sees the unchanged wire contract.
 *
 * Retries republish {@link #text()} verbatim. {@link #verify()} lets the dispatcher refuse a payload whose stored
 * hash no longer matches (a permanent PAYLOAD_INTEGRITY failure).
 */
public record OutboxPayload(String text, String sha256, long epochSeconds) {

    public OutboxPayload {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("payload text is required");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("payload hash must be 64 lowercase hex characters");
    }

    public static OutboxPayload create(UUID commandId, String capability, String command, Object value,
                                       long epochSeconds, ObjectMapper mapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", commandId.toString());
        payload.put("capability", capability);
        payload.put("command", command);
        payload.put("value", value);
        payload.put("timestamp", epochSeconds);
        try {
            String text = mapper.writeValueAsString(payload);
            return new OutboxPayload(text, sha256Hex(text), epochSeconds);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("command payload cannot be serialised", e);
        }
    }

    /** True when the stored hash is the hash of the stored text. */
    public boolean verify() {
        return sha256.equals(sha256Hex(text));
    }

    public byte[] bytes() {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
