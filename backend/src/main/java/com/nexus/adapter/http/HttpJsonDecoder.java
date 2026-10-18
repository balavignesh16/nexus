package com.nexus.adapter.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryPayload;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * Strict decoding of the HTTP wire contract (adapter-contract.md sections 6 to 8). It applies the rules of the JSON Schemas in
 * {@code contracts/v1} and of the standalone Python validator, which is its oracle: a differential test runs the whole parity corpus through
 * both and requires the same verdict and the same fixed code. Unlike the tolerant MQTT path, a message is exactly one UTF-8 JSON document
 * with unique member names and no byte-order mark, canonical UUID text, an integer epoch second where one is specified, and (for a version
 * above the highest known minor) strict known-field validation with {@code requires} rejection.
 *
 * <p>The decoder never throws for bad input. It carries no payload text in any result.
 */
public final class HttpJsonDecoder {
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern INSTANT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern REQUIRES_ITEM = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final BigInteger EPOCH_MIN = new BigInteger("-31557014167219200");
    private static final BigInteger EPOCH_MAX = new BigInteger("31556889864403199");
    private static final int HIGHEST_MINOR = 0;
    private static final java.util.Set<Integer> SUPPORTED_MAJORS = java.util.Set.of(1);

    /** A status claim posted by a device: the plain text online or offline (a claim, not a connectivity transition). */
    public record StatusClaim(boolean online) {}

    private final ObjectMapper strict = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private final ObjectMapper plain = new ObjectMapper();

    // ------------------------------------------------------------------ parsing and version resolution

    /** The parsed document, or null when the bytes are not exactly one strict JSON document. */
    private JsonNode parse(byte[] body) {
        if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) return null;
        try {
            JsonNode node = strict.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private record Version(RejectionCode failure, boolean forwardMinor) {}

    /** The version rule of section 6, a total function over the body member and the optional header. */
    private static Version resolve(JsonNode root, String header) {
        boolean present = root.has("contractVersion");
        JsonNode member = root.get("contractVersion");
        if (header != null && present && !(member.isTextual() && member.asText().equals(header))) {
            return new Version(RejectionCode.CONTRACT_VERSION_CONFLICT, false);
        }
        if (header == null && !present) return new Version(null, false);
        String value = header != null ? header : (member.isTextual() ? member.asText() : null);
        if (value == null || !VERSION.matcher(value).matches()) return new Version(RejectionCode.INVALID_CONTRACT_VERSION, false);
        String[] parts = value.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (!SUPPORTED_MAJORS.contains(major)) return new Version(RejectionCode.UNSUPPORTED_CONTRACT_MAJOR, false);
        return new Version(null, minor > HIGHEST_MINOR);
    }

    private static boolean validRequires(JsonNode root) {
        if (!root.has("requires")) return true;
        JsonNode requires = root.get("requires");
        if (!requires.isArray() || requires.size() > 8) return false;
        for (JsonNode item : requires) {
            if (!item.isTextual() || !REQUIRES_ITEM.matcher(item.asText()).matches()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ telemetry

    public Decoded<TelemetryPayload> decodeTelemetry(byte[] body, String headerVersion) {
        JsonNode root = parse(body);
        if (root == null || !root.isObject()) return Decoded.refused(RejectionCode.MALFORMED_JSON);
        Version version = resolve(root, headerVersion);
        if (version.failure() != null) return Decoded.refused(version.failure());

        boolean schemaFailed = !telemetrySchemaValid(root);
        RejectionCode code = classifyTelemetry(root, schemaFailed);
        if (code != null) return Decoded.refused(code);
        if (root.has("requires") && root.get("requires").size() > 0) return Decoded.refused(RejectionCode.UNSUPPORTED_REQUIRED_FEATURE);

        try {
            return Decoded.accepted(new TelemetryPayload(java.util.UUID.fromString(root.get("deviceId").asText()),
                    OffsetDateTime.parse(root.get("timestamp").asText()).toInstant(), text(root, "sensorType"), plain.readValue(strict.writeValueAsBytes(root.get("value")), Object.class),
                    text(root, "unit"), text(root, "capabilityKey"),
                    root.hasNonNull("readbackCommandId") ? java.util.UUID.fromString(root.get("readbackCommandId").asText()) : null, text(root, "messageId")));
        } catch (IOException | RuntimeException e) {
            return Decoded.refused(RejectionCode.INVALID_FIELDS);
        }
    }

    private static String text(JsonNode root, String field) {
        return root.hasNonNull(field) ? root.get(field).asText() : null;
    }

    private static boolean isUuid(JsonNode n) {
        return n != null && n.isTextual() && UUID_TEXT.matcher(n.asText()).matches();
    }

    private static boolean isInstant(JsonNode n) {
        if (n == null || !n.isTextual() || !INSTANT.matcher(n.asText()).matches()) return false;
        try {
            LocalDateTime.parse(n.asText().substring(0, 19));
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static boolean hasNonWhitespace(String s) {
        return s.codePoints().anyMatch(cp -> !isPythonWhitespace(cp));
    }

    /** The characters Python's str.isspace() treats as whitespace, which is what the schema pattern \S and the oracle use. */
    private static boolean isPythonWhitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 || (cp >= 0x1C && cp <= 0x1F);
    }

    private static boolean stringOrNull(JsonNode n) {
        return n == null || n.isNull() || n.isTextual();
    }

    private static boolean isNumber(JsonNode n) {
        return n != null && n.isNumber();
    }

    /** The JSON Schema contracts/v1/telemetry.schema.json. */
    private static boolean telemetrySchemaValid(JsonNode d) {
        if (!d.has("deviceId") || !d.has("timestamp") || !d.has("value")) return false;
        if (!validRequires(d)) return false;
        if (!isUuid(d.get("deviceId")) || !isInstant(d.get("timestamp"))) return false;
        for (String field : new String[]{"sensorType", "unit", "capabilityKey"}) if (!stringOrNull(d.get(field))) return false;
        JsonNode readback = d.get("readbackCommandId");
        if (readback != null && !readback.isNull() && !isUuid(readback)) return false;
        JsonNode messageId = d.get("messageId");
        if (messageId != null && !messageId.isNull() && !(messageId.isTextual() && MESSAGE_ID.matcher(messageId.asText()).matches())) return false;
        if (d.get("value").isNull()) return false;
        JsonNode key = d.get("capabilityKey");
        boolean generic = key != null && key.isTextual() && hasNonWhitespace(key.asText());
        if (generic) return true;
        if (!d.has("sensorType") || !d.has("unit")) return false;
        JsonNode sensor = d.get("sensorType"), unit = d.get("unit");
        return sensor.isTextual() && hasNonWhitespace(sensor.asText()) && unit.isTextual() && hasNonWhitespace(unit.asText()) && isNumber(d.get("value"));
    }

    private static boolean jacksonNull(JsonNode n) {
        return n == null || n.isNull() || (n.isTextual() && n.asText().isEmpty());
    }

    /** Maps a telemetry object to the fixed code the MQTT listener reports, in its order (nexus_contract.rules.classify_telemetry). */
    private static RejectionCode classifyTelemetry(JsonNode d, boolean schemaFailed) {
        boolean mappingFailure = false;
        for (String field : new String[]{"deviceId", "readbackCommandId"}) {
            JsonNode n = d.get(field);
            if (!jacksonNull(n) && !isUuid(n)) mappingFailure = true;
        }
        JsonNode timestamp = d.get("timestamp");
        if (!jacksonNull(timestamp) && !isInstant(timestamp)) mappingFailure = true;
        for (String field : new String[]{"sensorType", "unit", "capabilityKey"}) {
            JsonNode n = d.get(field);
            if (n != null && (n.isObject() || n.isArray())) mappingFailure = true;
        }
        if (mappingFailure) return RejectionCode.MALFORMED_JSON;
        JsonNode messageId = d.get("messageId");
        if (messageId != null && !messageId.isNull() && !messageId.isTextual()) return RejectionCode.INVALID_MESSAGE_ID;
        if (messageId != null && messageId.isTextual() && !MESSAGE_ID.matcher(messageId.asText()).matches()) return RejectionCode.INVALID_MESSAGE_ID;
        if (jacksonNull(d.get("deviceId")) || jacksonNull(timestamp) || d.get("value") == null || d.get("value").isNull()) return RejectionCode.INVALID_FIELDS;
        JsonNode key = d.get("capabilityKey");
        boolean generic = key != null && key.isTextual() && !key.asText().strip().isEmpty() && hasNonWhitespace(key.asText());
        if (!generic) {
            JsonNode sensor = d.get("sensorType"), unit = d.get("unit");
            boolean sensorBlank = sensor == null || !sensor.isTextual() || !hasNonWhitespace(sensor.asText());
            boolean unitBlank = unit == null || !unit.isTextual() || !hasNonWhitespace(unit.asText());
            if (sensorBlank || unitBlank || !isNumber(d.get("value"))) return RejectionCode.INVALID_LEGACY_PAYLOAD;
        }
        return schemaFailed ? RejectionCode.INVALID_FIELDS : null;
    }

    // ------------------------------------------------------------------ acknowledgement

    public Decoded<AckPayload> decodeAcknowledgement(byte[] body, String headerVersion) {
        JsonNode root = parse(body);
        if (root == null) return Decoded.refused(RejectionCode.MALFORMED_JSON);
        if (!root.isObject()) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        Version version = resolve(root, headerVersion);
        if (version.failure() != null) return Decoded.refused(version.failure());
        if (!ackSchemaValid(root)) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        if (root.has("requires") && root.get("requires").size() > 0) return Decoded.refused(RejectionCode.UNSUPPORTED_REQUIRED_FEATURE);
        try {
            return Decoded.accepted(new AckPayload(java.util.UUID.fromString(root.get("commandId").asText()), AckPayload.Status.valueOf(root.get("status").asText()),
                    Instant.ofEpochSecond(root.get("timestamp").bigIntegerValue().longValueExact()), text(root, "reason")));
        } catch (RuntimeException e) {
            return Decoded.refused(RejectionCode.INVALID_FIELDS);
        }
    }

    private static boolean ackSchemaValid(JsonNode d) {
        if (!d.has("commandId") || !d.has("status") || !d.has("timestamp") || !validRequires(d)) return false;
        if (!isUuid(d.get("commandId"))) return false;
        JsonNode status = d.get("status");
        if (!status.isTextual() || !(status.asText().equals("ACCEPTED") || status.asText().equals("REJECTED"))) return false;
        JsonNode timestamp = d.get("timestamp");
        if (!timestamp.isIntegralNumber()) return false;
        BigInteger epoch = timestamp.bigIntegerValue();
        if (epoch.compareTo(EPOCH_MIN) < 0 || epoch.compareTo(EPOCH_MAX) > 0) return false;
        JsonNode reason = d.get("reason");
        return reason == null || reason.isNull() || (reason.isTextual() && reason.asText().codePointCount(0, reason.asText().length()) <= 256);
    }

    // ------------------------------------------------------------------ status (plain text)

    /** The bare text online or offline: UTF-8 (invalid bytes become U+FFFD), String.trim() semantics, case-sensitive. No envelope, no version. */
    public Decoded<StatusClaim> decodeStatus(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.equals("online")) return Decoded.accepted(new StatusClaim(true));
        if (text.equals("offline")) return Decoded.accepted(new StatusClaim(false));
        return Decoded.refused(RejectionCode.INVALID_FIELDS);
    }
}
