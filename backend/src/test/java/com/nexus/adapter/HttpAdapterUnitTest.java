package com.nexus.adapter;

import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.http.CredentialStore;
import com.nexus.adapter.http.HttpAdapterSettings;
import com.nexus.adapter.http.HttpIngestController;
import com.nexus.adapter.http.HttpWebhookAdapter;
import com.nexus.adapter.http.KeyFormat;
import com.nexus.adapter.http.KeyHasher;
import com.nexus.adapter.spi.AcknowledgementIntake;
import com.nexus.adapter.spi.TelemetryIntake;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Startup guards (P5-064), the constant-time structure of verification (P5-055) and the TLS guard on every request. */
class HttpAdapterUnitTest {
    static final byte[] PEPPER = "unit-test-pepper-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static HttpAdapterSettings settings(boolean forwarded, String address) {
        return new HttpAdapterSettings(8192, 2, Duration.ofSeconds(60), Duration.ofSeconds(5), forwarded, address);
    }

    @Test
    void p5_064_theAdapterRefusesToStartWithCredentialsOnANonLoopbackBind() {
        for (String ok : new String[]{"127.0.0.1", "127.0.0.2", "::1", "localhost"}) settings(false, ok);
        for (String unsafe : new String[]{"", "0.0.0.0", "192.168.1.20", "10.0.0.5", "::", "example.invalid", "8.8.8.8"}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> settings(false, unsafe), unsafe);
            org.junit.jupiter.api.Assertions.assertTrue(refused.getMessage().contains("refusing to start"), refused.getMessage());
        }
        // behind a TLS-terminating proxy it may bind elsewhere, and then every request must prove the proxy saw https
        settings(true, "0.0.0.0");
        settings(true, "");
    }

    @Test
    void theOtherSettingsAreBoundedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(100, 2, Duration.ofSeconds(60), Duration.ofSeconds(5), false, "127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(1_000_000, 2, Duration.ofSeconds(60), Duration.ofSeconds(5), false, "127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(8192, 0, Duration.ofSeconds(60), Duration.ofSeconds(5), false, "127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(8192, 17, Duration.ofSeconds(60), Duration.ofSeconds(5), false, "127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(8192, 2, Duration.ofSeconds(14), Duration.ofSeconds(5), false, "127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(8192, 2, Duration.ofSeconds(301), Duration.ofSeconds(5), false, "127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> new HttpAdapterSettings(8192, 2, Duration.ofSeconds(60), Duration.ofMinutes(2), false, "127.0.0.1"));
        HttpAdapterSettings defaults = settings(false, "127.0.0.1");
        assertEquals(8192, defaults.maxBodyBytes());
        assertEquals(2, defaults.perDeviceConcurrency());
        assertEquals(Duration.ofSeconds(60), defaults.contactEligibilityWindow());
        new HttpAdapterSettings(8192, 2, Duration.ofSeconds(15), Duration.ZERO, false, "127.0.0.1");
        new HttpAdapterSettings(8192, 2, Duration.ofSeconds(300), Duration.ofSeconds(60), false, "127.0.0.1");
    }

    @Test
    void p5_064_everyRequestMustCarryXForwardedProtoHttpsWhenTheProxyModeIsOn() throws Exception {
        CredentialStore store = mock(CredentialStore.class);
        HttpWebhookAdapter adapter = new HttpWebhookAdapter(store, new KeyHasher(PEPPER));
        HttpIngestController controller = new HttpIngestController(adapter, settings(true, "0.0.0.0"), mock(TelemetryIntake.class), mock(AcknowledgementIntake.class), store,
                new AdapterMetrics(new SimpleMeterRegistry()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        UUID device = UUID.randomUUID();
        String path = "/api/v1/device-adapter/devices/" + device + "/telemetry";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("TLS_REQUIRED"));
        mvc.perform(post(path).header("X-Forwarded-Proto", "http").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Forwarded-Proto", "https").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verify(store, never()).find(anyString());
    }

    @Test
    void p5_055_verificationAlwaysHashesAndComparesInConstantTimeWhateverTheOutcome() {
        KeyHasher spyHasher = spy(new KeyHasher(PEPPER));
        CredentialStore store = mock(CredentialStore.class);
        HttpWebhookAdapter adapter = new HttpWebhookAdapter(store, spyHasher);
        SecureRandom random = new SecureRandom();
        String lookup = KeyFormat.newLookupId(random), secret = KeyFormat.newSecret(random);
        UUID device = UUID.randomUUID();
        byte[] hash = spyHasher.hash(secret);
        org.mockito.Mockito.clearInvocations(spyHasher);
        when(store.find(lookup)).thenReturn(Optional.of(new CredentialStore.Stored(UUID.randomUUID(), device, lookup, hash, KeyHasher.ALGORITHM, "ACTIVE", null, Instant.now(), "ACTIVE", "HTTP_WEBHOOK")));
        when(store.find(eq("f".repeat(24)))).thenReturn(Optional.empty());

        String good = "Bearer " + KeyFormat.format(lookup, secret);
        String[] attempts = {good, "Bearer " + KeyFormat.format(lookup, "A".repeat(43)), "Bearer " + KeyFormat.format("f".repeat(24), secret), "Bearer garbage", "", null, "Basic abc",
                "Bearer " + KeyFormat.format(lookup, secret).substring(1)};
        int expected = 0;
        for (String header : attempts) {
            adapter.authenticateHeader(header);
            expected++;
            verify(spyHasher, times(expected)).matches(any(), any());   // exactly one constant-time comparison per attempt, whatever the outcome
        }
        org.junit.jupiter.api.Assertions.assertTrue(adapter.authenticateHeader(good).ok());
        org.junit.jupiter.api.Assertions.assertFalse(adapter.authenticateHeader(attempts[1]).ok());
        // the comparison itself is MessageDigest.isEqual (no early exit); a one-byte difference anywhere gives no match
        for (int i = 0; i < hash.length; i += 7) {
            byte[] changed = hash.clone();
            changed[i] ^= 1;
            org.junit.jupiter.api.Assertions.assertFalse(new KeyHasher(PEPPER).matches(changed, secret));
        }
        // every failure is the same generic answer and none reveals which part was wrong
        for (String header : attempts) {
            if (header != null && header.equals(good)) continue;
            var result = adapter.authenticateHeader(header);
            assertEquals(com.nexus.adapter.spi.RejectionCode.UNAUTHENTICATED, result.failure());
            assertEquals(null, result.credential());
        }
    }

    @Test
    void aRetiringCredentialStopsAtItsInstantAndARevokedOneNeverWorks() {
        KeyHasher hasher = new KeyHasher(PEPPER);
        CredentialStore store = mock(CredentialStore.class);
        HttpWebhookAdapter adapter = new HttpWebhookAdapter(store, hasher);
        SecureRandom random = new SecureRandom();
        String lookup = KeyFormat.newLookupId(random), secret = KeyFormat.newSecret(random);
        Instant now = Instant.parse("2026-10-18T12:00:00Z");
        String header = "Bearer " + KeyFormat.format(lookup, secret);
        UUID device = UUID.randomUUID();
        for (Object[] row : new Object[][]{{"ACTIVE", null, true}, {"RETIRING", now.plusSeconds(1), true}, {"RETIRING", now, false}, {"RETIRING", now.minusSeconds(1), false},
                {"RETIRING", null, false}, {"REVOKED", null, false}, {"PAUSED", null, false}}) {
            when(store.find(lookup)).thenReturn(Optional.of(new CredentialStore.Stored(UUID.randomUUID(), device, lookup, hasher.hash(secret), KeyHasher.ALGORITHM,
                    (String) row[0], (Instant) row[1], now, "ACTIVE", "HTTP_WEBHOOK")));
            assertEquals(row[2], adapter.authenticateHeader(header).ok(), row[0] + " retire=" + row[1]);
        }
        when(store.find(lookup)).thenReturn(Optional.of(new CredentialStore.Stored(UUID.randomUUID(), device, lookup, hasher.hash(secret), "OTHER-ALGO", "ACTIVE", null, now, "ACTIVE", "HTTP_WEBHOOK")));
        org.junit.jupiter.api.Assertions.assertFalse(adapter.authenticateHeader(header).ok(), "an unknown hash algorithm is never trusted");
        when(store.find(lookup)).thenReturn(Optional.of(new CredentialStore.Stored(UUID.randomUUID(), device, lookup, hasher.hash(secret), KeyHasher.ALGORITHM, "ACTIVE", null, now, "MAINTENANCE", "HTTP_WEBHOOK")));
        assertEquals(com.nexus.adapter.spi.RejectionCode.FORBIDDEN_DEVICE, adapter.authenticateHeader(header).failure(), "a valid key of an inactive device is refused as forbidden");
    }
}
