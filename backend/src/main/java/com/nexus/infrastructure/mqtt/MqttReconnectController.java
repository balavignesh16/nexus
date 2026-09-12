package com.nexus.infrastructure.mqtt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NEXUS-owned MQTT reconnect controller.
 *
 * Paho's own automaticReconnect is disabled (see MQTTConfiguration); this
 * class is the sole source of reconnect attempts, using one dedicated,
 * single-thread scheduler and a small explicit state machine
 * (Tier 3 Phase 2 / 2A / 2B / 2C).
 *
 * Paho callbacks (connectComplete/connectionLost) are treated as
 * authoritative statements of current reality and are honored from any live
 * state, not only the state the controller expects them from - this is
 * required because Paho can deliver a genuine connectionLost while the
 * controller still believes it is CONNECTING (Phase 2A), and can deliver a
 * late connectComplete for an attempt the controller already gave up on
 * after its bounded wait expired (Phase 2C).
 *
 * IMPORTANT LIMITATION: this controller bounds how long NEXUS's own thread
 * waits for a Paho connect() call to resolve (via MqttClient.setTimeToWait,
 * configured in MQTTConfiguration). It does NOT fix, and cannot fix, the
 * underlying Eclipse Paho 1.2.5 ConnectBG thread-lifecycle defect. A connect
 * attempt that hits that defect may still leak one Paho-internal, non-daemon
 * thread, exactly as it does today; this controller only guarantees that
 * NEXUS's own scheduler thread is never permanently blocked by it. Client
 * recreation (a separate, not-yet-implemented mitigation for the leak
 * itself) is explicitly out of scope here.
 */
@Component
public class MqttReconnectController {

    private static final Logger log = LoggerFactory.getLogger(MqttReconnectController.class);

    enum State { STOPPED, DISCONNECTED, CONNECTING, CONNECTED, RETRY_WAIT }

    static final long INITIAL_DELAY_MS = 1000L;
    static final long MAX_DELAY_MS = 30_000L;

    private final MqttClient mqttClient;
    private final MqttConnectOptions connectOptions;
    private final ScheduledExecutorService scheduler;

    private final Counter reconnectAttempts;
    private final Counter reconnectSuccesses;
    private final Counter reconnectFailures;
    private final Counter connectionLosses;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private volatile State state = State.DISCONNECTED;
    private long currentDelayMs = INITIAL_DELAY_MS;
    private ScheduledFuture<?> pendingRetry;
    private volatile Instant lastSuccessfulConnect;

    @Autowired
    public MqttReconnectController(MqttClient mqttClient, MqttConnectOptions connectOptions, MeterRegistry registry) {
        this(mqttClient, connectOptions, registry,
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "mqtt-reconnect-controller")));
    }

    // Visible for testing: allows a test-controlled scheduler to be injected
    // so retry timing can be verified/driven without real multi-second waits.
    MqttReconnectController(MqttClient mqttClient, MqttConnectOptions connectOptions, MeterRegistry registry,
                             ScheduledExecutorService scheduler) {
        this.mqttClient = mqttClient;
        this.connectOptions = connectOptions;
        this.scheduler = scheduler;

        this.reconnectAttempts = registry.counter("mqtt.reconnect.attempts");
        this.reconnectSuccesses = registry.counter("mqtt.reconnect.successes");
        this.reconnectFailures = registry.counter("mqtt.reconnect.failures");
        this.connectionLosses = registry.counter("mqtt.connection.losses");
        registry.gauge("mqtt.reconnect.consecutive.failures", consecutiveFailures);
        registry.gauge("mqtt.connection.seconds.since.last.success", this,
                MqttReconnectController::secondsSinceLastSuccess);
    }

    @PostConstruct
    synchronized void init() {
        if (mqttClient.isConnected()) {
            state = State.CONNECTED;
            lastSuccessfulConnect = Instant.now();
            log.info("MQTT reconnect controller starting: initial connection already established");
        } else {
            log.warn("MQTT reconnect controller starting: no initial connection established; scheduling first reconnect attempt");
            state = State.DISCONNECTED;
            scheduleRetry();
        }
    }

    private double secondsSinceLastSuccess() {
        Instant last = lastSuccessfulConnect;
        if (last == null) {
            return -1;
        }
        return Duration.between(last, Instant.now()).getSeconds();
    }

    /**
     * Notifies the controller that Paho reported the connection lost. Per
     * Phase 2A/2C, this is honored (and schedules a retry) whenever the
     * controller currently believes it is CONNECTED or CONNECTING - a
     * genuine connectionLost can legitimately arrive during either. Any
     * other state means a retry is already pending or the controller has
     * been shut down, so the event is a safe no-op.
     */
    public synchronized void onConnectionLost(Throwable cause) {
        if (state == State.STOPPED) {
            log.debug("Ignoring connectionLost after shutdown", cause);
            return;
        }
        if (state == State.CONNECTED || state == State.CONNECTING) {
            connectionLosses.increment();
            log.warn("MQTT connection lost (previous controller state: {}); scheduling reconnect", state, cause);
            scheduleRetry();
        } else {
            log.debug("Ignoring connectionLost; controller already in state {}", state);
        }
    }

    /**
     * Notifies the controller that Paho reported a successful connection.
     * Per Phase 2C, this is treated as an authoritative statement of current
     * reality and always drives the controller to CONNECTED, even if it
     * arrives after the controller itself gave up waiting on this exact
     * attempt (a "late" connectComplete) - the connection is genuinely up
     * either way, and TelemetryMQTTListener will already have (re)subscribed
     * on it independently of the controller's own bookkeeping.
     */
    public synchronized void onConnectComplete() {
        if (state == State.STOPPED) {
            log.debug("Ignoring late connectComplete after shutdown");
            return;
        }
        State previous = state;
        cancelPendingRetryLocked();
        state = State.CONNECTED;
        currentDelayMs = INITIAL_DELAY_MS;
        consecutiveFailures.set(0);
        lastSuccessfulConnect = Instant.now();
        reconnectSuccesses.increment();
        log.info("MQTT connection established (previous controller state: {})", previous);
    }

    private void scheduleRetry() {
        if (state == State.STOPPED) {
            return;
        }
        if (pendingRetry != null && !pendingRetry.isDone()) {
            log.debug("A reconnect attempt is already scheduled/in flight; not scheduling another");
            return;
        }
        state = State.RETRY_WAIT;
        long delay = currentDelayMs;
        currentDelayMs = Math.min(currentDelayMs * 2, MAX_DELAY_MS);
        pendingRetry = scheduler.schedule(this::attemptConnect, delay, TimeUnit.MILLISECONDS);
        log.info("Scheduled next MQTT reconnect attempt in {} ms", delay);
    }

    private void attemptConnect() {
        synchronized (this) {
            if (state != State.RETRY_WAIT) {
                // Superseded by a success (or shutdown) that arrived while this
                // attempt was waiting to fire - never call connect() while
                // already CONNECTED (or STOPPED).
                log.debug("Skipping stale scheduled reconnect attempt; controller state is {}", state);
                return;
            }
            state = State.CONNECTING;
            // This task has now started running (and is about to block inside
            // the synchronous connect() call below, possibly for the full
            // bounded timeToWait). Its own ScheduledFuture will not report
            // isDone() until this method returns, so it must be cleared here,
            // not left referenced - otherwise scheduleRetry()'s "one pending
            // attempt" guard would incorrectly block the NEXT retry from ever
            // being scheduled once this attempt fails, permanently stalling
            // the controller on the very first stuck/failed connect.
            pendingRetry = null;
        }
        reconnectAttempts.increment();
        log.info("Attempting MQTT reconnect...");
        try {
            mqttClient.connect(connectOptions);
            log.info("MQTT reconnect attempt returned successfully (connectComplete will confirm subscription)");
        } catch (MqttException e) {
            handleAttemptFailure(e);
        }
    }

    private synchronized void handleAttemptFailure(MqttException e) {
        if (state == State.STOPPED) {
            return;
        }
        reconnectFailures.increment();
        consecutiveFailures.incrementAndGet();
        log.warn("MQTT reconnect attempt failed: {}", e.getMessage());
        if (state == State.CONNECTING) {
            scheduleRetry();
        }
        // If state is no longer CONNECTING here, a late connectComplete for
        // this same attempt already raced in and moved the controller to
        // CONNECTED - the connection is genuinely up, nothing to do.
    }

    private void cancelPendingRetryLocked() {
        if (pendingRetry != null) {
            pendingRetry.cancel(false);
            pendingRetry = null;
        }
    }

    /**
     * Deterministic shutdown: stop accepting new retry work, cancel anything
     * pending, stop the scheduler, then disconnect the MQTT client through
     * the existing lifecycle. Consolidated here (rather than split across
     * this class and MQTTConfiguration) specifically so the controller is
     * guaranteed to stop scheduling before the client is disconnected.
     */
    @PreDestroy
    public synchronized void shutdown() {
        state = State.STOPPED;
        cancelPendingRetryLocked();
        scheduler.shutdownNow();
        log.info("MQTT reconnect controller stopped");
        if (mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                log.info("Disconnected from MQTT broker.");
            } catch (MqttException e) {
                log.error("Error disconnecting from MQTT broker", e);
            }
        }
    }

    // Visible for testing
    State getState() {
        return state;
    }
}
