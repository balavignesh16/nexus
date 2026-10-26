package com.nexus.adapter.conformance;

import com.nexus.contract.PythonOracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The payloads and verdicts every adapter must agree on. There is ONE table for all transports. The payloads are the frozen firmware fixtures
 * (contracts/fixtures/firmware-v1) and a few built variations; the expected verdicts are the standalone contract validator's (the schema
 * authority), and {@code SharedCasesOracleTest} re-derives them from the validator on every run, so this file cannot drift into a second source
 * of truth. A case on which a transport legitimately differs does not belong here: it belongs in contracts/parity/known-differences.json with a
 * class and a reason (the MQTT path's tolerant legacy behaviour is exercised by WireContractParityTest, not hidden here).
 */
public final class SharedCases {
    public static final UUID DEVICE = UUID.fromString("3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13");
    public static final UUID COMMAND = UUID.fromString("a1b2c3d4-e5f6-4789-8abc-def012345678");

    public enum Kind { TELEMETRY, ACKNOWLEDGEMENT, STATUS }

    /** {@code expected} is "ACCEPT" or "REJECT:CODE". */
    public record Case(String id, Kind kind, byte[] payload, String expected, String corpusId) {
        public Case(String id, Kind kind, byte[] payload, String expected) {
            this(id, kind, payload, expected, null);
        }

        public boolean accepts() { return expected.equals("ACCEPT"); }
    }

    private SharedCases() {}

    private static byte[] fixture(String name) {
        try {
            return Files.readAllBytes(PythonOracle.repositoryRoot().resolve("contracts/fixtures/firmware-v1").resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("fixture " + name, e);
        }
    }

    /** The payload text of an item of the parity corpus, so that a case and its registry entry are about the very same bytes. */
    private static byte[] corpus(String id) {
        try {
            for (var item : new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    Files.readAllBytes(PythonOracle.repositoryRoot().resolve("contracts/parity/corpus.json"))).get("items")) {
                if (item.get("id").asText().equals(id)) return item.get("payloadText").asText().getBytes(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("corpus", e);
        }
        throw new IllegalStateException("no corpus item " + id);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String telemetryBody(String extra) {
        return "{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":24.5,\"unit\":\"CELSIUS\"" + extra + "}";
    }

    public static List<Case> telemetry() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("telemetry:legacy-firmware-fixture", Kind.TELEMETRY, fixture("telemetry-temperature.json"), "ACCEPT"));
        cases.add(new Case("telemetry:legacy-simulator-fixture", Kind.TELEMETRY, fixture("telemetry-simulator-legacy.json"), "ACCEPT"));
        cases.add(new Case("telemetry:generic-boolean-fixture", Kind.TELEMETRY, fixture("telemetry-simulator-generic-bool.json"), "ACCEPT"));
        cases.add(new Case("telemetry:readback-fixture", Kind.TELEMETRY, fixture("telemetry-simulator-readback.json"), "ACCEPT"));
        cases.add(new Case("telemetry:versioned-with-message-id", Kind.TELEMETRY,
                text(telemetryBody(",\"contractVersion\":\"1.0\",\"messageId\":\"" + UUID.fromString("00000001-0000-4000-8000-000000000000") + "\"")), "ACCEPT"));
        cases.add(new Case("telemetry:unknown-minor-is-accepted", Kind.TELEMETRY, text(telemetryBody(",\"contractVersion\":\"1.9\"")), "ACCEPT"));
        cases.add(new Case("telemetry:unknown-optional-field-is-ignored", Kind.TELEMETRY, text(telemetryBody(",\"contractVersion\":\"1.0\",\"futureField\":{\"a\":1}")), "ACCEPT"));
        cases.add(new Case("telemetry:unsupported-major", Kind.TELEMETRY, corpus("telemetry:contractVersion-2.0-field"), "REJECT:UNSUPPORTED_CONTRACT_MAJOR", "telemetry:contractVersion-2.0-field"));
        cases.add(new Case("telemetry:invalid-version-text", Kind.TELEMETRY, corpus("telemetry:contractVersion-invalid-text"), "REJECT:INVALID_CONTRACT_VERSION", "telemetry:contractVersion-invalid-text"));
        cases.add(new Case("telemetry:required-feature-not-understood", Kind.TELEMETRY, corpus("telemetry:requires-non-empty"), "REJECT:UNSUPPORTED_REQUIRED_FEATURE", "telemetry:requires-non-empty"));
        cases.add(new Case("telemetry:malformed-json", Kind.TELEMETRY, fixture("telemetry-malformed-json.json"), "REJECT:MALFORMED_JSON"));
        cases.add(new Case("telemetry:legacy-missing-unit", Kind.TELEMETRY, fixture("telemetry-legacy-missing-unit.json"), "REJECT:INVALID_LEGACY_PAYLOAD"));
        cases.add(new Case("telemetry:legacy-string-value", Kind.TELEMETRY, fixture("telemetry-legacy-string-value.json"), "REJECT:INVALID_LEGACY_PAYLOAD"));
        cases.add(new Case("telemetry:missing-device-id", Kind.TELEMETRY, fixture("telemetry-missing-deviceId.json"), "REJECT:INVALID_FIELDS"));
        cases.add(new Case("telemetry:device-id-not-a-uuid", Kind.TELEMETRY, fixture("telemetry-non-uuid-deviceId.json"), "REJECT:MALFORMED_JSON"));
        cases.add(new Case("telemetry:malformed-message-id", Kind.TELEMETRY, fixture("telemetry-bad-messageId.json"), "REJECT:INVALID_MESSAGE_ID"));
        cases.add(new Case("telemetry:empty-body", Kind.TELEMETRY, new byte[0], "REJECT:MALFORMED_JSON"));
        cases.add(new Case("telemetry:json-array-instead-of-object", Kind.TELEMETRY, text("[1,2]"), "REJECT:MALFORMED_JSON"));
        return cases;
    }

    public static List<Case> acknowledgements() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("ack:accepted-firmware-fixture", Kind.ACKNOWLEDGEMENT, fixture("ack-accepted.json"), "ACCEPT"));
        cases.add(new Case("ack:rejected-stale-firmware-fixture", Kind.ACKNOWLEDGEMENT, fixture("ack-rejected-stale.json"), "ACCEPT"));
        cases.add(new Case("ack:rejected-unsupported-firmware-fixture", Kind.ACKNOWLEDGEMENT, fixture("ack-rejected-unsupported.json"), "ACCEPT"));
        cases.add(new Case("ack:rejected-malformed-firmware-fixture", Kind.ACKNOWLEDGEMENT, fixture("ack-rejected-malformed.json"), "ACCEPT"));
        cases.add(new Case("ack:versioned", Kind.ACKNOWLEDGEMENT,
                text("{\"contractVersion\":\"1.0\",\"commandId\":\"" + COMMAND + "\",\"status\":\"ACCEPTED\",\"timestamp\":1767225600}"), "ACCEPT"));
        cases.add(new Case("ack:unsupported-major", Kind.ACKNOWLEDGEMENT, corpus("ack:contractVersion-2.0-field"), "REJECT:UNSUPPORTED_CONTRACT_MAJOR", "ack:contractVersion-2.0-field"));
        cases.add(new Case("ack:lowercase-status", Kind.ACKNOWLEDGEMENT, fixture("ack-lowercase-status.json"), "REJECT:INVALID_FIELDS"));
        cases.add(new Case("ack:numeric-command-id", Kind.ACKNOWLEDGEMENT, fixture("ack-numeric-commandId.json"), "REJECT:INVALID_FIELDS"));
        cases.add(new Case("ack:float-timestamp", Kind.ACKNOWLEDGEMENT, fixture("ack-float-timestamp.json"), "REJECT:INVALID_FIELDS"));
        cases.add(new Case("ack:malformed-json", Kind.ACKNOWLEDGEMENT, text("{\"commandId\":"), "REJECT:MALFORMED_JSON"));
        return cases;
    }

    public static List<Case> statuses() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("status:online", Kind.STATUS, fixture("status-online.txt"), "ACCEPT"));
        cases.add(new Case("status:offline", Kind.STATUS, fixture("status-offline.txt"), "ACCEPT"));
        cases.add(new Case("status:padded-is-trimmed", Kind.STATUS, text("  online\r\n"), "ACCEPT"));
        cases.add(new Case("status:comparison-is-case-sensitive", Kind.STATUS, fixture("status-uppercase.txt"), "REJECT:INVALID_FIELDS"));
        cases.add(new Case("status:json-envelope-is-not-a-status", Kind.STATUS, fixture("status-json-envelope.txt"), "REJECT:INVALID_FIELDS"));
        cases.add(new Case("status:empty", Kind.STATUS, new byte[0], "REJECT:INVALID_FIELDS"));
        return cases;
    }
}
