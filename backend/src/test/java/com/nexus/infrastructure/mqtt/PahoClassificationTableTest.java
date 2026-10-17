package com.nexus.infrastructure.mqtt;

import com.nexus.action.outbox.BrokerCommandPublisher.Attempt;
import com.nexus.action.outbox.BrokerCommandPublisher.Result;
import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxRetryPolicy;
import com.nexus.action.outbox.OutboxTerminalReason;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ledger P4-040: a TABLE-DRIVEN proof of the failure classification and of what each class may lead to. The table is built from
 * EVERY reason code the Paho client defines ({@code MqttException.REASON_CODE_*}, by reflection), so a code added by a client upgrade
 * is classified by the conservative default and is caught here.
 *
 * Classes: transient = proven NOT_SENT (the only class that may be retried); uncertain = everything else raised by the client; permanent
 * = a payload that fails its integrity check (never published, never retried). The retry policy is exercised for each class.
 */
class PahoClassificationTableTest {
    private static final byte[] BYTES = "{\"commandId\":\"c\",\"timestamp\":1}".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    /** The only reason codes proven to be raised BEFORE the client stores or queues a message. */
    private static final Set<Integer> PROVEN_PRE_HANDOVER = Set.of((int) MqttException.REASON_CODE_CLIENT_NOT_CONNECTED,
            (int) MqttException.REASON_CODE_CLIENT_CLOSED, (int) MqttException.REASON_CODE_CLIENT_DISCONNECTING, (int) MqttException.REASON_CODE_MAX_INFLIGHT);

    private static Map<String, Integer> everyReasonCode() throws IllegalAccessException {
        Map<String, Integer> codes = new TreeMap<>();
        for (Field f : MqttException.class.getFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("REASON_CODE_") && f.getType() == short.class)
                codes.put(f.getName(), (int) f.getShort(null));
        return codes;
    }

    private Attempt handOverFailsWith(int reasonCode) throws Exception {
        MqttClient client = mock(MqttClient.class);
        MqttTopic topic = mock(MqttTopic.class);
        when(client.isConnected()).thenReturn(true);
        when(client.getTopic("t")).thenReturn(topic);
        when(topic.publish(any(MqttMessage.class))).thenThrow(new MqttException(reasonCode));
        return new PahoBrokerCommandPublisher(client).publish("t", BYTES, TIMEOUT);
    }

    @Test
    void everyReasonCodeTheClientDefinesIsClassifiedAndOnlyTheProvenPreHandoverOnesAreTransient() throws Exception {
        Map<String, Integer> codes = everyReasonCode();
        assertTrue(codes.size() >= 20, "the table covers every reason code of the client: " + codes.size());
        int transients = 0;
        for (var e : codes.entrySet()) {
            Attempt attempt = handOverFailsWith(e.getValue());
            Result expected = PROVEN_PRE_HANDOVER.contains(e.getValue()) ? Result.NOT_SENT : Result.UNCERTAIN;
            assertEquals(expected, attempt.result(), e.getKey() + " (" + e.getValue() + ")");
            if (expected == Result.NOT_SENT) transients++;
        }
        assertEquals(PROVEN_PRE_HANDOVER.size(), transients, "exactly the four proven pre-handover refusals are transient");
    }

    @Test
    void eachClassLeadsToExactlyItsAllowedRetryDecision() throws Exception {
        var settings = new com.nexus.action.outbox.CommandOutboxSettings(false, Duration.ofSeconds(15), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0,
                Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), 4);
        var policy = new OutboxRetryPolicy(settings, () -> 0.5);
        for (var e : everyReasonCode().entrySet()) {
            Result result = handOverFailsWith(e.getValue()).result();
            AttemptResult mapped = result == Result.NOT_SENT ? AttemptResult.NOT_SENT : AttemptResult.UNCERTAIN;   // the dispatcher's one-to-one mapping
            if (mapped == AttemptResult.NOT_SENT) {
                assertTrue(policy.decide(mapped, 1, 3, Duration.ofSeconds(10), "X").retry(), "transient " + e.getKey() + " is retried within budget and window");
                assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, policy.decide(mapped, 3, 3, Duration.ofSeconds(10), "X").reason());
            } else {
                assertThrows(IllegalArgumentException.class, () -> policy.decide(mapped, 1, 3, Duration.ofSeconds(10), "X"),
                        "uncertain " + e.getKey() + " can never be retried");
            }
        }
        // permanent: an integrity failure is not a client outcome at all and is refused by the policy as well
        assertThrows(IllegalArgumentException.class, () -> policy.decide(AttemptResult.PAYLOAD_INTEGRITY, 1, 3, Duration.ofSeconds(10), "PAYLOAD_HASH_MISMATCH"));
    }

    @Test
    void theOtherBranchesOfThePublisherAreClassifiedTheSameWay() throws Exception {
        MqttClient client = mock(MqttClient.class);
        MqttTopic topic = mock(MqttTopic.class);
        MqttDeliveryToken token = mock(MqttDeliveryToken.class);
        PahoBrokerCommandPublisher publisher = new PahoBrokerCommandPublisher(client);
        // not connected: transient
        when(client.isConnected()).thenReturn(false);
        assertEquals(Result.NOT_SENT, publisher.publish("t", BYTES, TIMEOUT).result());
        // connected, hand-over accepted, token never completes: uncertain
        when(client.isConnected()).thenReturn(true);
        when(client.getTopic("t")).thenReturn(topic);
        when(topic.publish(any(MqttMessage.class))).thenReturn(token);
        when(token.isComplete()).thenReturn(false);
        assertEquals(Result.UNCERTAIN, publisher.publish("t", BYTES, TIMEOUT).result());
        // connected, hand-over accepted, token completes cleanly: confirmed (no retry needed)
        when(token.isComplete()).thenReturn(true);
        when(token.getException()).thenReturn(null);
        assertEquals(Result.CONFIRMED, publisher.publish("t", BYTES, TIMEOUT).result());
        assertFalse(Set.of(Result.values()).isEmpty());
    }
}
