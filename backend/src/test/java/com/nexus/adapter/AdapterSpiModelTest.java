package com.nexus.adapter;

import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.spi.AcknowledgementDecoder;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.AuthenticatedDevice;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.ConnectivitySource;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.DeviceAuthenticator;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.Rejection;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TransportKind;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The value types and capability interfaces of the SPI: closed rejection vocabulary, no-payload rejections, separate capabilities, fixed metric tags. */
class AdapterSpiModelTest {

    private static String contractText() throws IOException {
        Path p = Path.of("../architecture/phase5/adapter-contract.md");
        if (!Files.exists(p)) p = Path.of("architecture/phase5/adapter-contract.md");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void p5_013_theRejectionVocabularyIsExactlyTheOneInTheContract() throws IOException {
        String text = contractText();
        String paragraph = text.substring(text.indexOf("**Rejection vocabulary"), text.indexOf("## 8."));
        Set<String> documented = new TreeSet<>();
        Matcher m = Pattern.compile("`([A-Z][A-Z_]+)`").matcher(paragraph);
        while (m.find()) documented.add(m.group(1));
        Set<String> implemented = new TreeSet<>();
        for (RejectionCode code : RejectionCode.values()) implemented.add(code.name());
        assertEquals(documented, implemented, "a code was added or removed without changing adapter-contract.md section 7 (or the reverse)");
        assertEquals(17, implemented.size());
        for (String name : implemented) assertTrue(name.matches("[A-Z_]{1,64}"), name);
    }

    @Test
    void p5_034_aRejectionCarriesOnlyTheFixedCodeAndItsMetricTagsToo() {
        Rejection rejection = Rejection.permanent(RejectionCode.INVALID_MESSAGE_ID);
        assertEquals("INVALID_MESSAGE_ID/PERMANENT", rejection.toString());
        assertEquals(2, Rejection.class.getRecordComponents().length, "a rejection has a code and a classification and nothing else");
        RawMessage secretPayload = new RawMessage("SECRET-PAYLOAD-xyz".getBytes(StandardCharsets.UTF_8), "nexus/devices/x/telemetry");
        assertFalse(secretPayload.toString().contains("SECRET"), "a raw message never prints its bytes");
        CommandEnvelope envelope = new CommandEnvelope(UUID.randomUUID(), UUID.randomUUID(), "SECRET-COMMAND".getBytes(StandardCharsets.UTF_8), "hash", 1L, "c");
        assertFalse(envelope.toString().contains("SECRET"));
        assertFalse(new AuthenticatedDevice(UUID.randomUUID(), "lookup123").toString().contains("lookup123"), "a credential reference is not printed");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new AdapterMetrics(meters).rejected("mqtt-json", rejection.code());
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> assertTrue(tag.getValue().matches("[A-Za-z0-9_.-]{1,40}"), tag.getKey() + "=" + tag.getValue()));
        }
    }

    @Test
    void p5_031_capabilitiesAreSeparateInterfacesWithoutNoOpMethods() {
        List<Class<?>> interfaces = List.of(TelemetryDecoder.class, AcknowledgementDecoder.class, ConnectivitySource.class, CommandEgress.class,
                DeviceAuthenticator.class, AdapterLifecycle.class);
        assertEquals(interfaces.size(), new HashSet<>(interfaces).size());
        assertEquals(new HashSet<>(interfaces), new HashSet<>(AdapterCapability.contracts().values()), "one capability per interface");
        for (Class<?> type : interfaces) {
            assertTrue(type.isInterface());
            assertTrue(type.getInterfaces().length == 0, type + " extends nothing, so no capability inherits another's methods");
            List<Method> abstractMethods = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(method.isDefault(), type + "." + method.getName() + " must not be a default (no-op) method");
                abstractMethods.add(method);
            }
            assertTrue(abstractMethods.size() >= 1 && abstractMethods.size() <= 2, type + " has " + abstractMethods.size() + " methods");
        }
        // A decoder cannot deliver and an egress cannot decode: no interface is a superset of another.
        assertFalse(CommandEgress.class.isAssignableFrom(TelemetryDecoder.class));
        assertFalse(TelemetryDecoder.class.isAssignableFrom(CommandEgress.class));
    }

    @Test
    void anAttemptStatesItsOutcomeConservatively() {
        assertEquals(EvidenceKind.HTTP_SERVED, Attempt.confirmed(EvidenceKind.HTTP_SERVED).evidence());
        assertThrows(NullPointerException.class, () -> new Attempt(Attempt.Outcome.CONFIRMED, null, null), "CONFIRMED must state its evidence");
        assertThrows(IllegalArgumentException.class, () -> new Attempt(Attempt.Outcome.NOT_SENT, null, null), "NOT_SENT needs a code");
        assertThrows(IllegalArgumentException.class, () -> Attempt.uncertain("has space"));
        assertThrows(IllegalArgumentException.class, () -> new Attempt(Attempt.Outcome.UNCERTAIN, "X", EvidenceKind.BROKER_CONFIRMED));
        for (EvidenceKind kind : EvidenceKind.values()) assertTrue(kind.name().matches("^[A-Z0-9_]{1,64}$"), "fits the history reason_code format");
    }

    @Test
    void transportKindsAreExactlyTheV41Values() {
        assertEquals(Set.of("MQTT_JSON", "HTTP_WEBHOOK", "SIMULATOR"), Set.of(TransportKind.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        assertTrue(TransportKind.parse("HTTP_WEBHOOK").isPresent());
        for (String bad : new String[]{"http_webhook", "HTTP", "", " MQTT_JSON", "BLE", null}) assertTrue(TransportKind.parse(bad).isEmpty(), String.valueOf(bad));
    }

    @Test
    void decodedIsAValueOrARejectionAndNothingElse() {
        assertTrue(Decoded.accepted("x").isAccepted());
        Decoded<String> refused = Decoded.refused(RejectionCode.MALFORMED_JSON);
        assertFalse(refused.isAccepted());
        assertEquals(Rejection.Classification.PERMANENT, ((Decoded.Refused<String>) refused).rejection().classification());
        assertThrows(NullPointerException.class, () -> Decoded.accepted(null));
    }

    @Test
    void p5_119_adapterMetersUseOnlyTheFixedTagKeysOfSection17() throws IOException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AdapterMetrics metrics = new AdapterMetrics(meters);
        metrics.received("mqtt-json", "accepted");
        metrics.rejected("mqtt-json", RejectionCode.TRANSPORT_MISMATCH);
        metrics.contract("legacy-v1");
        metrics.egress("http-webhook", Attempt.Outcome.UNCERTAIN);
        metrics.authFailure(RejectionCode.UNAUTHENTICATED);
        java.util.Map<String, Set<String>> expected = java.util.Map.of(
                "nexus.adapter.received", Set.of("adapter", "result"), "nexus.adapter.rejected", Set.of("adapter", "reason"),
                "nexus.adapter.contract", Set.of("version"), "nexus.adapter.egress", Set.of("adapter", "outcome"),
                "nexus.adapter.auth.failures", Set.of("reason"));
        assertEquals(expected.keySet(), meters.getMeters().stream().map(m -> m.getId().getName()).collect(java.util.stream.Collectors.toSet()));
        for (Meter meter : meters.getMeters()) {
            Set<String> keys = new HashSet<>();
            meter.getId().getTags().forEach(tag -> keys.add(tag.getKey()));
            assertEquals(expected.get(meter.getId().getName()), keys, meter.getId().getName());
            meter.getId().getTags().forEach(tag -> assertFalse(tag.getValue().matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no identifier as a tag value"));
        }
        String section = contractText();
        String observability = section.substring(section.indexOf("## 17."), section.indexOf("## 18."));
        for (String name : expected.keySet()) assertTrue(observability.contains(name), name + " is documented in section 17");
    }
}
