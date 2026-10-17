package com.nexus.infrastructure.mqtt;

import com.nexus.action.outbox.BrokerCommandPublisher.Attempt;
import com.nexus.action.outbox.BrokerCommandPublisher.Result;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Classification table of the bounded publisher (contract R10): the blocking {@code MqttClient.publish} is never used, the
 * wait is bounded by the supplied timeout, and every failure is classified as definite non-publication or uncertain.
 */
class PahoBrokerCommandPublisherTest {
    private static final byte[] BYTES = "{\"commandId\":\"c\",\"timestamp\":1}".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofMillis(750);

    private final MqttClient client = mock(MqttClient.class);
    private final MqttTopic topic = mock(MqttTopic.class);
    private final MqttDeliveryToken token = mock(MqttDeliveryToken.class);
    private final PahoBrokerCommandPublisher publisher = new PahoBrokerCommandPublisher(client);

    private void wired() throws Exception {
        when(client.isConnected()).thenReturn(true);
        when(client.getTopic("nexus/devices/d/commands")).thenReturn(topic);
        when(topic.publish(any(MqttMessage.class))).thenReturn(token);
    }

    @Test
    void aClientThatIsNotConnectedIsADefiniteNonPublicationAndNothingIsHandedOver() throws Exception {
        when(client.isConnected()).thenReturn(false);
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.NOT_SENT, a.result());
        assertEquals("CLIENT_NOT_CONNECTED", a.code());
        verify(client, never()).getTopic(any());
        verify(client, never()).publish(any(String.class), any(MqttMessage.class));
    }

    @Test
    void aRefusalWhileHandingTheMessageOverIsADefiniteNonPublication() throws Exception {
        wired();
        when(topic.publish(any(MqttMessage.class))).thenThrow(new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED));
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.NOT_SENT, a.result());
        assertEquals("CLIENT_REFUSED_" + MqttException.REASON_CODE_CLIENT_NOT_CONNECTED, a.code());
    }

    @Test
    void anUnexpectedRuntimeErrorFromTheHandOverCallIsUncertainBecauseTheMessageMayAlreadyBeStored() throws Exception {
        wired();
        when(topic.publish(any(MqttMessage.class))).thenThrow(new IllegalStateException("boom"));
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.UNCERTAIN, a.result());
        assertEquals("HANDOVER_ERROR", a.code());
    }

    @Test
    void aCompletedDeliveryTokenIsConfirmedAndTheMessageIsQos1NotRetainedAndByteIdentical() throws Exception {
        wired();
        when(token.isComplete()).thenReturn(true);
        when(token.getException()).thenReturn(null);
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.CONFIRMED, a.result());
        ArgumentCaptor<MqttMessage> message = ArgumentCaptor.forClass(MqttMessage.class);
        verify(topic).publish(message.capture());
        assertEquals(1, message.getValue().getQos());
        assertFalse(message.getValue().isRetained());
        assertArrayEquals(BYTES, message.getValue().getPayload());
        verify(token).waitForCompletion(TIMEOUT.toMillis());
        verify(client, never()).publish(any(String.class), any(MqttMessage.class));
        verify(client, never()).publish(any(String.class), any(byte[].class), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aTimeoutWhileWaitingForCompletionIsUncertainBecauseTheMessageMayAlreadyBeOnTheWire() throws Exception {
        wired();
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).when(token).waitForCompletion(anyLong());
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.UNCERTAIN, a.result());
        assertEquals("PUBLISH_TIMEOUT", a.code());
        verify(token).waitForCompletion(TIMEOUT.toMillis());
    }

    @Test
    void aConnectionLossAfterTheHandOverIsUncertain() throws Exception {
        wired();
        doThrow(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST)).when(token).waitForCompletion(anyLong());
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.UNCERTAIN, a.result());
        assertTrue(a.code().startsWith("PUBLISH_FAILED_AFTER_HANDOFF_"), a.code());
    }

    @Test
    void anIncompleteTokenAfterTheWaitIsUncertainNeverConfirmed() throws Exception {
        wired();
        when(token.isComplete()).thenReturn(false);
        assertEquals(Result.UNCERTAIN, publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT).result());
    }

    @Test
    void aTokenCarryingAnExceptionIsNeverConfirmed() throws Exception {
        wired();
        when(token.isComplete()).thenReturn(true);
        when(token.getException()).thenReturn(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST));
        assertEquals(Result.UNCERTAIN, publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT).result());
    }

    @Test
    void everyListedPreHandoverRefusalIsADefiniteNonPublication() throws Exception {
        for (int code : PahoBrokerCommandPublisher.REFUSED_BEFORE_HANDOVER) {
            wired();
            when(topic.publish(any(MqttMessage.class))).thenThrow(new MqttException(code));
            Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
            assertEquals(Result.NOT_SENT, a.result(), "reason code " + code);
            assertEquals("CLIENT_REFUSED_" + code, a.code());
        }
        assertEquals(4, PahoBrokerCommandPublisher.REFUSED_BEFORE_HANDOVER.size(), "the list of proven pre-handover refusals is short and explicit");
    }

    @Test
    void anUnlistedReasonCodeFromTheHandOverCallIsUncertain() throws Exception {
        for (int code : new int[]{MqttException.REASON_CODE_CONNECTION_LOST, MqttException.REASON_CODE_CLIENT_TIMEOUT,
                MqttException.REASON_CODE_UNEXPECTED_ERROR, MqttException.REASON_CODE_WRITE_TIMEOUT, 99999}) {
            wired();
            when(topic.publish(any(MqttMessage.class))).thenThrow(new MqttException(code));
            Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
            assertEquals(Result.UNCERTAIN, a.result(), "reason code " + code);
            assertEquals("HANDOVER_FAILED_" + code, a.code());
        }
    }

    @Test
    void aPersistenceFailureWhileStoringTheMessageIsAProvenNonPublication() throws Exception {
        wired();
        when(topic.publish(any(MqttMessage.class))).thenThrow(new org.eclipse.paho.client.mqttv3.MqttPersistenceException());
        Attempt a = publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT);
        assertEquals(Result.NOT_SENT, a.result());
        assertTrue(a.code().startsWith("PERSISTENCE_REFUSED_"), a.code());
    }

    @Test
    void aFailureBuildingTheMessageOrResolvingTheTopicObjectHappensBeforeAnyClientIoSoItIsNotSent() throws Exception {
        when(client.isConnected()).thenReturn(true);
        when(client.getTopic("bad topic")).thenThrow(new IllegalArgumentException("invalid topic"));
        Attempt a = publisher.publish("bad topic", BYTES, TIMEOUT);
        assertEquals(Result.NOT_SENT, a.result());
        assertEquals("PREPARATION_FAILED", a.code());
        verify(topic, never()).publish(any(MqttMessage.class));
    }

    @Test
    void aDeliveryTokenTimeoutAndAConnectionLossAfterTheHandOverAreUncertainAndTheMessageIsNeverResent() throws Exception {
        wired();
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).when(token).waitForCompletion(anyLong());
        assertEquals(Result.UNCERTAIN, publisher.publish("nexus/devices/d/commands", BYTES, TIMEOUT).result());
        verify(topic, org.mockito.Mockito.times(1)).publish(any(MqttMessage.class));
    }
}
