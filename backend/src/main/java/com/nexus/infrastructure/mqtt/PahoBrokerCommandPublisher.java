package com.nexus.infrastructure.mqtt;

import com.nexus.action.outbox.BrokerCommandPublisher;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Bounded publish for the outbox dispatcher. {@code MqttClient.publish} blocks for up to the client's time-to-wait
 * (15 s here), so this class hands the message to the client without waiting ({@code getTopic().publish()} returns the
 * delivery token immediately) and waits on that token for at most the attempt timeout.
 *
 * Classification is deliberately conservative (contract R10). An outcome is NOT_SENT only when there is explicit evidence that
 * the message never entered the client's send path:
 * <ul>
 * <li>the client reports itself not connected before anything is handed over;</li>
 * <li>building the message or resolving the topic object fails (no client I/O has been attempted);</li>
 * <li>the client REFUSES the hand-over with one of a short list of reason codes that the Paho client raises before it persists
 *     or queues the message ({@link #REFUSED_BEFORE_HANDOVER}), or a persistence failure while storing it (the store is the
 *     first step; a message that could not be stored is not queued).</li>
 * </ul>
 * EVERYTHING else is UNCERTAIN, because the client may already hold the message in its in-flight state and resend it on
 * reconnect (clean session off, in-memory persistence): a timeout or failure while waiting for completion, a connection
 * loss, any unlisted reason code, and any unexpected runtime exception thrown by the hand-over call itself. Never resent here.
 */
@Component
public class PahoBrokerCommandPublisher implements BrokerCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(PahoBrokerCommandPublisher.class);
    private static final int COMMAND_QOS = 1;

    /**
     * Reason codes the Paho client raises BEFORE it persists or queues a message handed to it: not connected, client closed,
     * disconnect in progress, and the in-flight limit (rejected before the message is stored).
     */
    static final Set<Integer> REFUSED_BEFORE_HANDOVER = Set.of(
            (int) MqttException.REASON_CODE_CLIENT_NOT_CONNECTED,
            (int) MqttException.REASON_CODE_CLIENT_CLOSED,
            (int) MqttException.REASON_CODE_CLIENT_DISCONNECTING,
            (int) MqttException.REASON_CODE_MAX_INFLIGHT);

    private final MqttClient mqttClient;

    public PahoBrokerCommandPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    @Override
    public Attempt publish(String topic, byte[] payload, Duration timeout) {
        MqttMessage message;
        MqttTopic mqttTopic;
        try {
            if (!mqttClient.isConnected()) return Attempt.notSent("CLIENT_NOT_CONNECTED");
            message = new MqttMessage(payload);
            message.setQos(COMMAND_QOS);
            message.setRetained(false);
            mqttTopic = mqttClient.getTopic(topic);
        } catch (RuntimeException e) {
            // nothing has been handed to the client's send path yet
            log.warn("Command publish failed before the hand-over: {}", e.getClass().getSimpleName());
            return Attempt.notSent("PREPARATION_FAILED");
        }

        IMqttDeliveryToken token;
        try {
            token = mqttTopic.publish(message);
        } catch (MqttPersistenceException e) {
            log.warn("Command publish could not be stored by the client: reasonCode={}", e.getReasonCode());
            return Attempt.notSent("PERSISTENCE_REFUSED_" + e.getReasonCode());
        } catch (MqttException e) {
            if (REFUSED_BEFORE_HANDOVER.contains(e.getReasonCode())) {
                log.warn("Command publish refused before the hand-over: reasonCode={}", e.getReasonCode());
                return Attempt.notSent("CLIENT_REFUSED_" + e.getReasonCode());
            }
            log.warn("Command publish hand-over failed with an unlisted reason; outcome uncertain: reasonCode={}", e.getReasonCode());
            return Attempt.uncertain("HANDOVER_FAILED_" + e.getReasonCode());
        } catch (RuntimeException e) {
            log.warn("Command publish hand-over raised {}; outcome uncertain", e.getClass().getSimpleName());
            return Attempt.uncertain("HANDOVER_ERROR");
        }

        try {
            token.waitForCompletion(timeout.toMillis());
            if (token.isComplete() && token.getException() == null) return Attempt.confirmed();
            return Attempt.uncertain("PUBLISH_TIMEOUT");
        } catch (MqttException e) {
            log.warn("Command publish outcome is uncertain: reasonCode={}", e.getReasonCode());
            return Attempt.uncertain(e.getReasonCode() == MqttException.REASON_CODE_CLIENT_TIMEOUT
                    ? "PUBLISH_TIMEOUT" : "PUBLISH_FAILED_AFTER_HANDOFF_" + e.getReasonCode());
        } catch (RuntimeException e) {
            return Attempt.uncertain("PUBLISH_ERROR_AFTER_HANDOFF");
        }
    }
}
