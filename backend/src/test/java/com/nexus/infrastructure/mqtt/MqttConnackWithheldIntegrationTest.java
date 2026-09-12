package com.nexus.infrastructure.mqtt;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces, against the REAL Eclipse Paho 1.2.5 client (no mocking of
 * Paho's own connect/wait mechanism), the exact class of defect confirmed in
 * Tier 3 Phase 1/2B: a TCP connection that is accepted but never completes
 * the MQTT CONNECT/CONNACK handshake, leaving a Paho ConnectBG thread stuck
 * inside CommsSender.start(). Before MqttClient.setTimeToWait(...) was
 * bounded, this would block the calling thread - and therefore the
 * reconnect controller's single scheduler thread - forever (Phase 2B). This
 * test proves connect() now resolves within the configured bound and that
 * the reconnect controller subsequently makes a further, real attempt
 * rather than permanently stalling.
 *
 * This test does NOT prove the underlying Paho ConnectBG leak is fixed -
 * it isn't, and is not in scope here (Tier 3 Phase 1/2/2C). It only proves
 * NEXUS's own exposure to that defect is bounded.
 */
class MqttConnackWithheldIntegrationTest {

    private ServerSocket serverSocket;
    private volatile boolean acceptMore = true;
    private final AtomicInteger acceptedConnections = new AtomicInteger(0);

    private ServerSocket startConnackWithholdingBroker(long forceCloseAfterMillis) throws IOException {
        ServerSocket socket = new ServerSocket(0);
        Thread acceptThread = new Thread(() -> {
            while (acceptMore) {
                try {
                    Socket client = socket.accept();
                    acceptedConnections.incrementAndGet();
                    // Accept the TCP connection but never read or write
                    // anything, in particular never send a CONNACK - Paho's
                    // receiver thread blocks waiting for bytes that will
                    // never arrive, exactly the confirmed stuck-ConnectBG
                    // scenario (Tier 3 Phase 1).
                    //
                    // Real Mosquitto does not hold such a connection open
                    // forever: it force-closes a socket that never completes
                    // the MQTT CONNECT handshake after its own idle timeout
                    // (observed as ~91s in Tier 2 Phase 8D/8E). That forced
                    // close is what eventually unblocks the abandoned
                    // receiver thread, resolves Paho's internal CONNECTING
                    // state, and allows a subsequent attempt to actually
                    // proceed rather than being rejected immediately with
                    // CONNECT_IN_PROGRESS (confirmed by this test - see the
                    // Tier 3 Phase 2 implementation report). This fake broker
                    // reproduces that same shape of recovery, just on a much
                    // shorter timescale so the test runs quickly.
                    if (forceCloseAfterMillis > 0) {
                        Socket toClose = client;
                        Thread closer = new Thread(() -> {
                            try {
                                Thread.sleep(forceCloseAfterMillis);
                                toClose.close();
                            } catch (Exception ignored) {
                            }
                        }, "connack-withholding-broker-force-close");
                        closer.setDaemon(true);
                        closer.start();
                    }
                } catch (IOException e) {
                    // Expected once the socket is closed in tearDown.
                }
            }
        }, "connack-withholding-broker-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return socket;
    }

    @AfterEach
    void tearDown() throws IOException {
        acceptMore = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    @Test
    @Timeout(30)
    void connectDoesNotBlockIndefinitelyWhenConnackIsWithheld() throws Exception {
        serverSocket = startConnackWithholdingBroker(0); // never force-closed - not needed for a single connect() call
        int port = serverSocket.getLocalPort();

        MqttClient client = new MqttClient("tcp://localhost:" + port, "connack-withheld-test-client", new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(false);
        options.setConnectionTimeout(2); // short: only TCP needs to succeed, which it does immediately here
        long timeToWaitMs = (options.getConnectionTimeout() * 1000L) + 5000L; // same formula as MQTTConfiguration
        client.setTimeToWait(timeToWaitMs);

        long start = System.currentTimeMillis();
        MqttException thrown = assertThrows(MqttException.class, () -> client.connect(options));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(acceptedConnections.get() >= 1, "the fake broker should have accepted the TCP connection");
        assertEquals(MqttException.REASON_CODE_CLIENT_TIMEOUT, thrown.getReasonCode(),
                "connect() must fail with a client-side wait timeout, not hang forever");
        assertTrue(elapsed < timeToWaitMs + 5000,
                "connect() must return within the configured bound (" + timeToWaitMs + "ms); took " + elapsed + "ms");
        assertTrue(elapsed >= timeToWaitMs - 1000,
                "connect() should not return suspiciously earlier than the configured bound; took " + elapsed + "ms");
    }

    @Test
    @Timeout(90)
    void reconnectControllerRecoversToARealSecondAttemptOnceTheStrayConnectionIsForceClosed() throws Exception {
        // Force-closing the accepted-but-never-completed socket after a
        // short delay reproduces, on a fast timescale, the same shape of
        // recovery that real Mosquitto provides in production (its own
        // idle-connection timeout, observed as ~91s in Tier 2 Phase 8D/8E) -
        // see the long comment in startConnackWithholdingBroker() for why
        // this is necessary at all.
        serverSocket = startConnackWithholdingBroker(3000);
        int port = serverSocket.getLocalPort();

        MqttClient client = new MqttClient("tcp://localhost:" + port, "connack-withheld-controller-client", new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(false);
        options.setConnectionTimeout(2);
        client.setTimeToWait((options.getConnectionTimeout() * 1000L) + 5000L);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            MqttReconnectController controller =
                    new MqttReconnectController(client, options, new SimpleMeterRegistry(), scheduler);
            controller.init(); // client.isConnected() is false -> schedules the first real attempt

            // Documented, evidence-based expectation (confirmed by this test
            // during implementation, matching the Tier 3 Phase 2C analysis):
            // the FIRST real attempt genuinely times out after ~7s (bounded
            // timeToWait). Paho's own internal ClientComms state remains
            // CONNECTING after that, since nothing resolved it - so several
            // subsequent controller-scheduled retries fail immediately with
            // REASON_CODE_CONNECT_IN_PROGRESS (cheap, no new socket, no new
            // leaked thread) until the fake broker's forced close of the
            // original stray socket unblocks Paho's abandoned receiver
            // thread, which finally resolves the old connect token and
            // clears the internal state. Only then can a subsequent
            // scheduled retry proceed to make a genuine second TCP
            // connection. The controller's own scheduler thread is never
            // permanently blocked at any point in this sequence - this is
            // exactly the property Tier 3 Phase 2B/2C set out to prove.
            long deadline = System.currentTimeMillis() + 80_000;
            while (acceptedConnections.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            assertTrue(acceptedConnections.get() >= 2,
                    "expected at least 2 real TCP connect attempts (the first stuck attempt plus a genuine "
                            + "subsequent attempt after the stray connection was force-closed); got "
                            + acceptedConnections.get());
            // The second real attempt is itself CONNACK-withheld, so by the
            // moment this observes acceptedConnections >= 2 the controller
            // may legitimately already be CONNECTING (for that second
            // attempt) rather than still RETRY_WAIT - either is proof the
            // controller kept cycling rather than permanently stalling.
            MqttReconnectController.State state = controller.getState();
            assertTrue(state == MqttReconnectController.State.RETRY_WAIT
                            || state == MqttReconnectController.State.CONNECTING,
                    "controller must still be actively cycling (RETRY_WAIT or CONNECTING), not stuck or stopped; was " + state);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
