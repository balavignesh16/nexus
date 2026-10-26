package com.nexus.adapter.conformance;

import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.RawMessage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the conformance kit needs to know about one adapter under test. The kit decides WHAT to check from the adapter's declared
 * capabilities; a subject only says HOW to reach them (how to frame bytes for this transport, how to force a delivery outcome, how to
 * present credentials). A subject never decides whether a capability is tested: that is the descriptor's business, so a subject cannot hide
 * a missing capability, and the kit never runs a scenario for a capability that is not declared.
 */
public interface Subject {
    /** UNIT: no database or broker (the adapter's own logic over controllable collaborators). LIVE: the running application's beans. */
    enum Level { UNIT, LIVE }

    String name();

    Level level();

    AdapterDescriptor adapter();

    /** How raw bytes are framed for this transport. Required when the adapter decodes anything. */
    default Optional<Wire> wire() { return Optional.empty(); }

    /** The adapter's real receive path into a recording core. Optional; the ingest scenarios report NOT_APPLICABLE without it. */
    default Optional<IngestProbe> ingest() { return Optional.empty(); }

    /** A way to force the delivery outcomes of the adapter's command egress. Required when it declares COMMAND_EGRESS. */
    default Optional<EgressProbe> egress() { return Optional.empty(); }

    /** Credentials in the shapes the authenticator must distinguish. Required when it declares DEVICE_AUTHENTICATOR. */
    default Optional<AuthProbe> auth() { return Optional.empty(); }

    /**
     * Shared cases on which THIS transport legitimately gives another verdict, each tied to its entry in contracts/parity/known-differences.json
     * (a class, a clause and a reason). The kit then expects exactly the registered verdict, so a registered difference is still checked: it
     * fails when the behaviour moves in either direction. A difference that is not registered here fails the shared-case scenario.
     */
    default Map<String, Registered> registeredDifferences() { return Map.of(); }

    record Registered(String verdict, String registryCase) {}

    /** Scenario ids that do not apply to this transport, each with the reason. These are reported as NOT_APPLICABLE and never as PASS. */
    default Map<String, String> notApplicable() { return Map.of(); }

    /** Transport-specific framing of one message for the decoder under test. */
    interface Wire {
        RawMessage telemetry(byte[] payload, UUID device);

        RawMessage acknowledgement(byte[] payload, UUID device);

        RawMessage status(byte[] payload, UUID device);

        /** The message for a payload that names {@code payloadDevice} but is delivered on behalf of {@code addressedDevice}, or empty when identity is not part of the framing. */
        default Optional<RawMessage> telemetryForOtherDevice(byte[] payload, UUID addressedDevice) { return Optional.empty(); }
    }

    /** The real receive path of the adapter into a core that records what it is handed. */
    interface IngestProbe {
        enum Kind { TELEMETRY, ACKNOWLEDGEMENT, STATUS }

        /** Whether this transport's receive path has an input of the kind. */
        default boolean supports(Kind kind) { return true; }

        /** A device this subject can deliver for (a provisioned and correctly assigned device at LIVE level, any identifier at UNIT level). */
        java.util.UUID newDevice();

        /** The payload with the shared table's device id replaced by {@code device}. */
        default byte[] forDevice(byte[] payload, UUID device) {
            return new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .replace(SharedCases.DEVICE.toString(), device.toString()).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        /** Delivers {@code payload} the way the transport delivers it (a broker publish, an HTTP request, a frame) for {@code device}. Waits until the adapter finished with it. */
        void send(Kind kind, byte[] payload, UUID device);

        /** Calls the core's telemetry / acknowledgement / status entry points have received since {@link #reset()}. */
        int coreCalls(Kind kind);

        void reset();

        /** LIVE only: rows the core stored for the device, or -1 when the subject has no database. */
        default long storedTelemetry(UUID device) { return -1; }
    }

    /** Forces the three classified outcomes of one delivery attempt and reports what reached the wire. */
    interface EgressProbe {
        enum Mode { CONFIRMED, NOT_SENT, UNCERTAIN, WIRE_FAILS_WITH_EXCEPTION }

        record Delivery(Attempt attempt, int wireCalls, byte[] bytesOnWire, Throwable thrown) {}

        /** Delivers {@code payload} for the device in the requested mode, or empty when this subject cannot force it (reported as NOT_APPLICABLE with the reason from {@link #cannotForce}). */
        Optional<Delivery> deliver(Mode mode, UUID device, byte[] payload);

        /** Why a mode cannot be forced, for the report. */
        default String cannotForce(Mode mode) { return "this subject cannot force " + mode; }

        /** The evidence kind a CONFIRMED attempt of this transport must carry. */
        com.nexus.adapter.spi.EvidenceKind expectedEvidence();
    }

    interface AuthProbe {
        String validCredential();

        UUID deviceOfValidCredential();

        String wrongSecret();

        String unknownCredential();

        String revokedCredential();

        /** A credential of a device that is not ACTIVE, or null when the subject cannot create one. */
        default String inactiveDeviceCredential() { return null; }

        /** The message carrying the credential in this transport's own way. */
        RawMessage message(String credential);
    }
}
