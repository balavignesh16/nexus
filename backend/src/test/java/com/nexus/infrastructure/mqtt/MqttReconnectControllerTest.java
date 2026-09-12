package com.nexus.infrastructure.mqtt;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Tier 3 Phase 2/2A/2B/2C reconnect-controller state
 * machine. The real ScheduledExecutorService is replaced by a mock so retry
 * timing (1s/2s/4s/.../30s) can be asserted and driven synchronously,
 * without the test actually waiting real seconds. The single test that
 * requires genuinely real Paho connect()/wait behavior (the CONNACK-withheld
 * scenario) lives separately in MqttConnackWithheldIntegrationTest, per the
 * approved plan's explicit instruction not to fake Paho's own wait mechanism.
 */
class MqttReconnectControllerTest {

    private MqttClient mqttClient;
    private MqttConnectOptions options;
    private ScheduledExecutorService scheduler;
    private MqttReconnectController controller;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ScheduledFuture<?> fakeFuture(boolean done) {
        ScheduledFuture future = mock(ScheduledFuture.class);
        when(future.isDone()).thenReturn(done);
        return future;
    }

    @BeforeEach
    void setUp() {
        mqttClient = mock(MqttClient.class);
        options = new MqttConnectOptions();
        scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> fakeFuture(false));
    }

    private MqttReconnectController newController(boolean initiallyConnected) {
        when(mqttClient.isConnected()).thenReturn(initiallyConnected);
        controller = new MqttReconnectController(mqttClient, options, new SimpleMeterRegistry(), scheduler);
        controller.init();
        return controller;
    }

    private Runnable latestScheduledTask() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).schedule(captor.capture(), anyLong(), any(TimeUnit.class));
        return captor.getValue();
    }

    @Test
    void startsConnectedWhenInitialConnectAlreadySucceeded() {
        newController(true);

        assertEquals(MqttReconnectController.State.CONNECTED, controller.getState());
        verifyNoInteractions(scheduler);
    }

    @Test
    void startsBySchedulingFirstRetryWhenInitialConnectFailed() {
        newController(false);

        assertEquals(MqttReconnectController.State.RETRY_WAIT, controller.getState());
        verify(scheduler).schedule(any(Runnable.class), eq(MqttReconnectController.INITIAL_DELAY_MS), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void connectionLostWhileConnectedSchedulesRetry() {
        newController(true);

        controller.onConnectionLost(new RuntimeException("boom"));

        assertEquals(MqttReconnectController.State.RETRY_WAIT, controller.getState());
        verify(scheduler).schedule(any(Runnable.class), eq(MqttReconnectController.INITIAL_DELAY_MS), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void connectionLostWhileConnectingSchedulesRetry() throws Exception {
        // Phase 2A fix: connectionLost must NOT be ignored while CONNECTING.
        newController(false);
        Runnable firstAttempt = latestScheduledTask();
        // Simulate the attempt reaching CONNECTING and connect() returning
        // normally while the application-level connectComplete has not yet
        // arrived (Phase 2B: notifyComplete() can precede the app callback).
        firstAttempt.run();
        assertEquals(MqttReconnectController.State.CONNECTING, controller.getState());

        controller.onConnectionLost(new RuntimeException("failed mid-connect"));

        assertEquals(MqttReconnectController.State.RETRY_WAIT, controller.getState());
    }

    @Test
    void connectExceptionDuringAttemptSchedulesNextRetryWithDoubledDelay() throws Exception {
        newController(false);
        Runnable firstAttempt = latestScheduledTask();
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).when(mqttClient).connect(any(MqttConnectOptions.class));

        firstAttempt.run();

        assertEquals(MqttReconnectController.State.RETRY_WAIT, controller.getState());
        verify(scheduler).schedule(any(Runnable.class), eq(2000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void backoffDoublesAcrossRepeatedFailuresUpToTheCap() throws Exception {
        newController(false); // schedules attempt #1 at the initial 1000ms delay (asserted separately above)
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).when(mqttClient).connect(any(MqttConnectOptions.class));

        // Each failed attempt schedules the NEXT one at the next delay in the sequence.
        long[] expectedNextDelays = {2000L, 4000L, 8000L, 16000L, 30000L, 30000L};
        for (long expected : expectedNextDelays) {
            Runnable attempt = latestScheduledTask();
            reset(scheduler);
            when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> fakeFuture(false));
            attempt.run();
            verify(scheduler).schedule(any(Runnable.class), eq(expected), eq(TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void successfulConnectCompleteResetsBackoffAndConsecutiveFailures() throws Exception {
        newController(false);
        Runnable firstAttempt = latestScheduledTask();
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).when(mqttClient).connect(any(MqttConnectOptions.class));
        firstAttempt.run(); // failure #1 -> next scheduled delay is 2000ms
        reset(scheduler);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> fakeFuture(false));

        controller.onConnectComplete();
        assertEquals(MqttReconnectController.State.CONNECTED, controller.getState());

        // A subsequent loss must schedule the NEXT retry back at the initial delay, not the doubled one.
        controller.onConnectionLost(new RuntimeException("dropped again"));
        verify(scheduler).schedule(any(Runnable.class), eq(MqttReconnectController.INITIAL_DELAY_MS), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void duplicateConnectionLostDoesNotScheduleASecondRetry() {
        newController(true);

        controller.onConnectionLost(new RuntimeException("first"));
        controller.onConnectionLost(new RuntimeException("duplicate"));

        verify(scheduler, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void lateConnectCompleteAfterTimeoutRecoversToConnectedAndCancelsThePendingRetry() throws Exception {
        newController(false);
        ScheduledFuture<?> pending = fakeFuture(false);
        doReturn(pending).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        Runnable firstAttempt = latestScheduledTask();
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).when(mqttClient).connect(any(MqttConnectOptions.class));
        firstAttempt.run(); // -> RETRY_WAIT, a new retry scheduled and captured as "pending"

        // The abandoned attempt actually succeeds after NEXUS stopped waiting on it.
        controller.onConnectComplete();

        assertEquals(MqttReconnectController.State.CONNECTED, controller.getState());
        verify(pending).cancel(false);
    }

    @Test
    void staleScheduledAttemptDoesNothingIfAlreadyConnected() throws Exception {
        newController(false);
        Runnable firstAttempt = latestScheduledTask();

        controller.onConnectComplete(); // connection recovered through some other path
        firstAttempt.run(); // the now-stale scheduled task fires anyway

        verify(mqttClient, never()).connect(any(MqttConnectOptions.class));
        assertEquals(MqttReconnectController.State.CONNECTED, controller.getState());
    }

    @Test
    void shutdownCancelsPendingRetryStopsSchedulerAndDisconnects() throws Exception {
        ScheduledFuture<?> pending = fakeFuture(false);
        doReturn(pending).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        newController(false); // init() schedules the first attempt, which will resolve to `pending`
        latestScheduledTask();
        when(mqttClient.isConnected()).thenReturn(true);

        controller.shutdown();

        verify(pending).cancel(false);
        verify(scheduler).shutdownNow();
        verify(mqttClient).disconnect();
        assertEquals(MqttReconnectController.State.STOPPED, controller.getState());
    }

    @Test
    void shutdownDoesNotDisconnectIfAlreadyDisconnected() throws Exception {
        newController(false);
        when(mqttClient.isConnected()).thenReturn(false);

        controller.shutdown();

        verify(mqttClient, never()).disconnect();
    }

    @Test
    void noRetryScheduledAfterShutdown() {
        newController(true);
        controller.shutdown();
        reset(scheduler);

        controller.onConnectionLost(new RuntimeException("after shutdown"));

        verifyNoInteractions(scheduler);
        assertEquals(MqttReconnectController.State.STOPPED, controller.getState());
    }

    @Test
    void lateConnectCompleteAfterShutdownIsIgnored() {
        newController(true);
        controller.shutdown();

        controller.onConnectComplete();

        assertEquals(MqttReconnectController.State.STOPPED, controller.getState());
    }
}
