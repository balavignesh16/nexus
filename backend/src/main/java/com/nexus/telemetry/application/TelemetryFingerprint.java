package com.nexus.telemetry.application;

import com.nexus.telemetry.api.dto.TelemetryRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The fingerprint contract of the telemetry inbox (R17, decision A2): a lowercase hexadecimal SHA-256 over a deterministic
 * canonical text of the inputs that decide how a sample is processed.
 *
 * <p><b>Included</b>, each tagged and length-prefixed so no value can be mistaken for another field or type:
 * {@code sensorType}, {@code capabilityKey} (the capability identity exactly as supplied), the {@code value} with its JSON
 * type (null, boolean, number, text, object with keys sorted, array in order), {@code unit}, the device {@code timestamp}
 * (as epoch seconds and nanoseconds), and {@code readbackCommandId}. <b>Excluded</b>: the messageId itself (it is the key),
 * the device id (the key is per device), the transport ({@link TelemetrySource}), the server receipt time and every
 * generated value. An absent optional field is encoded as absent, never defaulted, so a missing field differs from an empty
 * one and no timestamp is ever invented.
 *
 * <p>Numbers are compared by numeric value, not by spelling: {@code 1}, {@code 1.0} and {@code 1.00} are the same number, so
 * JSON number formatting and property order cannot turn a retransmission into a conflict, while the text {@code "1"} and
 * the boolean {@code true} stay different from every number. Both entry points build the same {@link TelemetryRequest}, so
 * the fingerprint is the same over MQTT and HTTP for the same logical message.
 *
 * <p>Changing this encoding changes every fingerprint; the version prefix {@code v1} marks it.
 */
public final class TelemetryFingerprint {
    private static final String VERSION = "v1";

    private TelemetryFingerprint() {}

    public static String of(TelemetryRequest request) {
        return sha256Hex(canonical(request));
    }

    /** The exact text that is hashed; exposed for tests and documentation, never stored. */
    static String canonical(TelemetryRequest request) {
        StringBuilder out = new StringBuilder(VERSION);
        text(out, "sensorType", request.sensorType());
        text(out, "capabilityKey", request.capabilityKey());
        out.append("|value=");
        value(out, request.value());
        text(out, "unit", request.unit());
        out.append("|timestamp=").append(request.timestamp() == null ? "~" : instant(request.timestamp()));
        text(out, "readbackCommandId", request.readbackCommandId() == null ? null : request.readbackCommandId().toString().toLowerCase(java.util.Locale.ROOT));
        return out.toString();
    }

    private static String instant(Instant instant) {
        return instant.getEpochSecond() + "." + String.format("%09d", instant.getNano());
    }

    private static void text(StringBuilder out, String name, String value) {
        out.append('|').append(name).append('=');
        out.append(value == null ? "~" : lengthPrefixed(value));
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private static void value(StringBuilder out, Object value) {
        if (value == null) {
            out.append('z');
        } else if (value instanceof Boolean flag) {
            out.append("b:").append(flag);
        } else if (value instanceof Number number) {
            out.append("n:").append(number(number));
        } else if (value instanceof CharSequence sequence) {
            out.append("s:").append(lengthPrefixed(sequence.toString()));
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            out.append("m{");
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                out.append(lengthPrefixed(entry.getKey())).append('=');
                value(out, entry.getValue());
                out.append(';');
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            List<Object> items = new ArrayList<>(collection);
            out.append("a[");
            for (Object item : items) {
                value(out, item);
                out.append(';');
            }
            out.append(']');
        } else {
            out.append("o:").append(lengthPrefixed(value.getClass().getName() + "=" + value));
        }
    }

    /** Numeric value in plain decimal form without trailing zeros, so the spelling of a number never matters. */
    private static String number(Number number) {
        try {
            BigDecimal decimal = new BigDecimal(number.toString());
            return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notFinite) {
            return "!" + number;
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }
}
