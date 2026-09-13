package com.nexus.device.connectivity;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the MQTT-protocol-level assumptions the Tier 5B device-connectivity
 * design depends on, against a REAL eclipse-mosquitto broker and REAL Paho
 * 1.2.5 clients (the exact library version NEXUS uses) - not simulated or
 * assumed. This class does not involve the NEXUS Spring context at all;
 * DB-hydration/restart-recovery is proven separately in
 * InMemoryDeviceConnectivityRegistryTest using the same fresh-instance
 * pattern already established by RuleRegistryPersistenceIntegrationTest.
 *
 * The broker-restart test uses Docker's own "restart" operation on this
 * SAME container (never stop+recreate), so /mosquitto/data's persistence
 * file survives on the container's own internal filesystem without any
 * host bind mount - a host-path bind mount was tried and removed because
 * Mosquitto's non-root container user hit permission errors writing to a
 * Windows-host-mounted directory; no such mount is actually needed here,
 * since a plain container restart never touches the container's own
 * writable layer.
 */
@Testcontainers
class DeviceConnectivityMqttMechanicsTest {

    @Container
    static GenericContainer<?> mosquitto = buildMosquittoContainer();

    private static GenericContainer<?> buildMosquittoContainer() {
        return new GenericContainer<>("eclipse-mosquitto:2.0")
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort());
    }

    private String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    private MqttClient newClient(String clientId) throws Exception {
        return new MqttClient(brokerUrl(), clientId, new MemoryPersistence());
    }

    @AfterEach
    void tearDown() {
        // Nothing to clean up explicitly - each test uses its own client IDs
        // and topics; the container itself is reused across tests in this
        // class per @Container's default (class-scoped) lifecycle.
    }

    // ---------- retained-message delivery on (re)subscribe ----------

    @Test
    @Timeout(30)
    void retainedMessage_deliveredToASubscriberThatConnectsAfterItWasPublished() throws Exception {
        String topic = "nexus/devices/" + UUID.randomUUID() + "/status";

        MqttClient publisher = newClient("publisher-" + UUID.randomUUID());
        publisher.connect(basicOptions());
        publisher.publish(topic, new MqttMessage("offline".getBytes(StandardCharsets.UTF_8)) {{
            setQos(1);
            setRetained(true);
        }});
        publisher.disconnect();

        BlockingQueue<String> received = new ArrayBlockingQueue<>(1);
        MqttClient subscriber = newClient("subscriber-" + UUID.randomUUID());
        subscriber.connect(basicOptions());
        subscriber.setCallback(queueingCallback(received));
        subscriber.subscribe(topic, 1);

        String payload = received.poll(10, TimeUnit.SECONDS);
        assertEquals("offline", payload, "a fresh SUBSCRIBE must immediately receive the current retained message");

        subscriber.disconnect();
    }

    @Test
    @Timeout(30)
    void retainedMessage_reDeliveredOnExplicitResubscribe_mirroringNexusReconnectPattern() throws Exception {
        // Mirrors TelemetryMQTTListener.subscribe(): NEXUS always issues an
        // explicit subscribe() call on every connect/reconnect, rather than
        // relying on persistent-session resumption to skip it. This test
        // proves that pattern genuinely re-delivers the retained message
        // every time, which is what makes a backend restart (a fresh
        // connect + explicit subscribe) recover state via MQTT, not just
        // the database.
        String topic = "nexus/devices/" + UUID.randomUUID() + "/status";
        String clientId = "resubscribe-client-" + UUID.randomUUID();

        MqttClient publisher = newClient("publisher-" + UUID.randomUUID());
        publisher.connect(basicOptions());
        publisher.publish(topic, retainedMessage("online"));
        publisher.disconnect();

        // First connect + subscribe: receives it once.
        BlockingQueue<String> firstRound = new ArrayBlockingQueue<>(1);
        MqttClient subscriberFirst = newClient(clientId);
        MqttConnectOptions persistentOptions = basicOptions();
        persistentOptions.setCleanSession(false);
        subscriberFirst.connect(persistentOptions);
        subscriberFirst.setCallback(queueingCallback(firstRound));
        subscriberFirst.subscribe(topic, 1);
        assertEquals("online", firstRound.poll(10, TimeUnit.SECONDS));
        subscriberFirst.disconnect();

        // Second connect (simulating a backend restart) with the SAME
        // persistent-session client ID, explicitly re-subscribing again -
        // exactly TelemetryMQTTListener's pattern.
        BlockingQueue<String> secondRound = new ArrayBlockingQueue<>(1);
        MqttClient subscriberSecond = newClient(clientId);
        subscriberSecond.connect(persistentOptions);
        subscriberSecond.setCallback(queueingCallback(secondRound));
        subscriberSecond.subscribe(topic, 1);

        String payload = secondRound.poll(10, TimeUnit.SECONDS);
        assertEquals("online", payload,
                "an explicit re-subscribe after reconnect must re-deliver the retained message, "
                        + "regardless of cleanSession=false session resumption");
        subscriberSecond.disconnect();
    }

    // ---------- broker restart ----------

    @Test
    @Timeout(60)
    void retainedMessage_survivesBrokerContainerRestart() throws Exception {
        // Uses its OWN dedicated container, separate from the class-level
        // shared @Container: on this Windows/Docker Desktop test
        // environment, an in-place restart of a container was observed to
        // leave its HOST-side TCP port-forward unreliable for a JVM socket
        // connection for an extended period afterward (even though
        // `docker inspect` reports the identical port binding and the
        // container's own logs confirm Mosquitto itself restarted cleanly
        // and reloaded its persisted retained-message store). Isolating
        // this test's restart to a private container prevents that
        // environment-specific host-networking quirk from poisoning the
        // other tests in this class, which share one broker instance.
        try (GenericContainer<?> restartTarget = buildMosquittoContainer()) {
            restartTarget.start();
            String topic = "nexus/devices/" + UUID.randomUUID() + "/status";
            String url = "tcp://" + restartTarget.getHost() + ":" + restartTarget.getMappedPort(1883);

            MqttClient publisher = new MqttClient(url, "publisher-" + UUID.randomUUID(), new MemoryPersistence());
            publisher.connect(basicOptions());
            publisher.publish(topic, retainedMessage("online"));
            publisher.disconnect();

            // Restart the actual broker process inside the same container
            // (not a Testcontainers stop+recreate, which would allocate a
            // new container) - docker restart on the same container ID is
            // the faithful proxy for "the broker process died and came
            // back", exactly like `docker-compose restart mosquitto` in
            // production.
            restartTarget.getDockerClient().restartContainerCmd(restartTarget.getContainerId()).exec();
            awaitContainerRunning(restartTarget, Duration.ofSeconds(30));

            // Verified via mosquitto_sub run INSIDE the container (its own
            // network namespace) rather than through the host-mapped port,
            // for the reason explained above - this still proves the real
            // claim (the broker itself, restarted, still holds the
            // retained message) using the real broker and real MQTT
            // protocol, just sidestepping this local environment's
            // host-network re-establishment delay.
            org.testcontainers.containers.Container.ExecResult result = restartTarget.execInContainer(
                    "mosquitto_sub", "-h", "localhost", "-t", topic, "-C", "1", "-W", "15");
            assertEquals(0, result.getExitCode(), "mosquitto_sub must succeed: " + result.getStderr());
            assertEquals("online", result.getStdout().trim(),
                    "retained messages must survive a broker restart, given persistence=true and a durable data directory");
        }
    }

    private void awaitContainerRunning(GenericContainer<?> container, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Boolean running = container.getDockerClient().inspectContainerCmd(container.getContainerId())
                    .exec().getState().getRunning();
            if (Boolean.TRUE.equals(running)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Container did not report running again within " + timeout);
    }

    // ---------- rapid reconnect / duplicate client id ordering ----------

    @Test
    @Timeout(30)
    void rapidReconnect_sameClientId_finalRetainedStateIsOnline_notStaleOffline() throws Exception {
        // Verification 1's protocol claim, checked against a real broker:
        // client A holds the connection with a will of "offline"; client B
        // then connects with the SAME client ID, forcing the broker to
        // evict A (firing A's will) before B can complete its own connect
        // and publish "online". The final retained state must be "online",
        // not "offline" - proving the ordering guarantee empirically, not
        // just by spec citation.
        String topic = "nexus/devices/" + UUID.randomUUID() + "/status";
        String sharedClientId = "flapping-device-" + UUID.randomUUID();

        MqttClient clientA = newClient(sharedClientId);
        MqttConnectOptions optionsA = basicOptions();
        optionsA.setWill(topic, "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        clientA.connect(optionsA);
        assertNotNull(clientA, "client A must connect successfully before being taken over");

        MqttClient clientB = newClient(sharedClientId);
        MqttConnectOptions optionsB = basicOptions();
        optionsB.setWill(topic, "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        clientB.connect(optionsB); // forces the broker to disconnect clientA, firing its will
        clientB.publish(topic, retainedMessage("online"));

        BlockingQueue<String> finalState = new ArrayBlockingQueue<>(1);
        MqttClient observer = newClient("observer-" + UUID.randomUUID());
        observer.connect(basicOptions());
        observer.setCallback(queueingCallback(finalState));
        observer.subscribe(topic, 1);

        String payload = finalState.poll(10, TimeUnit.SECONDS);
        assertEquals("online", payload,
                "the new connection's birth message must win over the evicted old connection's will");

        clientB.disconnect();
        observer.disconnect();
    }

    // ---------- helpers ----------

    private MqttConnectOptions basicOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        return options;
    }

    private MqttMessage retainedMessage(String payload) {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(true);
        return message;
    }

    private MqttCallback queueingCallback(BlockingQueue<String> queue) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                queue.offer(new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        };
    }

}
