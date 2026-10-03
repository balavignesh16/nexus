package com.nexus.twin.application;

import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.domain.TwinUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for DigitalTwinService's update-decision logic (out-of-order
 * protection) and its delegation to DigitalTwinRegistry. The registry itself is
 * mocked here; real persistence/hydration behavior is covered separately in
 * InMemoryDigitalTwinRegistryTest against a real database.
 */
class DigitalTwinServiceTest {

    private DigitalTwinRegistry registry;
    private ApplicationEventPublisher eventPublisher;
    private RealtimePublisher realtimePublisher;
    private PipelineService pipelineService;
    private DigitalTwinService service;

    @BeforeEach
    void setUp() {
        registry = mock(DigitalTwinRegistry.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        realtimePublisher = mock(RealtimePublisher.class);
        pipelineService = mock(PipelineService.class);
        service = new DigitalTwinService(registry, eventPublisher, realtimePublisher, pipelineService);
    }

    @Test
    void updateTwin_noExistingTwin_createsAndPersistsNewTwin() {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        when(registry.get(deviceId, "TEMPERATURE_SENSOR")).thenReturn(Optional.empty());

        TelemetryRequest request = new TelemetryRequest(deviceId, timestamp, "TEMPERATURE_SENSOR", 22.5, "CELSIUS");

        service.updateTwin(request, "corr-1");

        ArgumentCaptor<DigitalTwin> twinCaptor = ArgumentCaptor.forClass(DigitalTwin.class);
        verify(registry).put(twinCaptor.capture());
        DigitalTwin persisted = twinCaptor.getValue();
        assertEquals(deviceId, persisted.deviceId());
        assertEquals(timestamp, persisted.latestTimestamp());
        assertEquals("TEMPERATURE_SENSOR", persisted.latestSensorType());
        assertEquals(22.5, persisted.latestValue());
        assertEquals("CELSIUS", persisted.latestUnit());
        assertTrue(persisted.metadata().isEmpty(), "current implementation always writes empty metadata on update");

        ArgumentCaptor<TwinUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(TwinUpdatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        TwinUpdatedEvent published = eventCaptor.getValue();
        assertNull(published.previousState(), "no prior twin existed, so previousState must be null");
        assertEquals(persisted, published.currentState());
        assertEquals("corr-1", published.correlationId());

        verify(realtimePublisher).publishTwinDelta(isNull(), eq(persisted), eq("corr-1"));
        verify(pipelineService).recordTrace(any());
    }

    @Test
    void updateTwin_newerTimestamp_updatesTwinAndPreservesPreviousState() {
        UUID deviceId = UUID.randomUUID();
        Instant earlier = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant later = Instant.now();

        DigitalTwin existing = new DigitalTwin(deviceId, earlier, "TEMPERATURE_SENSOR", 20.0, "CELSIUS", earlier, java.util.Map.of());
        when(registry.get(deviceId, "TEMPERATURE_SENSOR")).thenReturn(Optional.of(existing));

        TelemetryRequest request = new TelemetryRequest(deviceId, later, "TEMPERATURE_SENSOR", 25.0, "CELSIUS");

        service.updateTwin(request, "corr-2");

        ArgumentCaptor<DigitalTwin> twinCaptor = ArgumentCaptor.forClass(DigitalTwin.class);
        verify(registry).put(twinCaptor.capture());
        assertEquals(25.0, twinCaptor.getValue().latestValue());
        assertEquals(later, twinCaptor.getValue().latestTimestamp());

        ArgumentCaptor<TwinUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(TwinUpdatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(existing, eventCaptor.getValue().previousState());
        assertEquals(twinCaptor.getValue(), eventCaptor.getValue().currentState());
    }

    @Test
    void updateTwin_olderTimestamp_isIgnoredEntirely() {
        UUID deviceId = UUID.randomUUID();
        Instant current = Instant.now();
        Instant older = current.minus(1, ChronoUnit.MINUTES);

        DigitalTwin existing = new DigitalTwin(deviceId, current, "TEMPERATURE_SENSOR", 20.0, "CELSIUS", current, java.util.Map.of());
        when(registry.get(deviceId, "TEMPERATURE_SENSOR")).thenReturn(Optional.of(existing));

        TelemetryRequest staleRequest = new TelemetryRequest(deviceId, older, "TEMPERATURE_SENSOR", 99.0, "CELSIUS");

        service.updateTwin(staleRequest, "corr-3");

        verify(registry, never()).put(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(realtimePublisher, never()).publishTwinDelta(any(), any(), any());
        verify(pipelineService, never()).recordTrace(any());
    }

    @Test
    void updateTwin_exactlyEqualTimestamp_isIgnored_boundaryCase() {
        // isAfter() is strict; a message with the SAME timestamp as the current
        // twin must not be treated as newer.
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        DigitalTwin existing = new DigitalTwin(deviceId, timestamp, "TEMPERATURE_SENSOR", 20.0, "CELSIUS", timestamp, java.util.Map.of());
        when(registry.get(deviceId, "TEMPERATURE_SENSOR")).thenReturn(Optional.of(existing));

        TelemetryRequest sameTimestampRequest = new TelemetryRequest(deviceId, timestamp, "TEMPERATURE_SENSOR", 50.0, "CELSIUS");

        service.updateTwin(sameTimestampRequest, "corr-4");

        verify(registry, never()).put(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void getTwin_delegatesToRegistry() {
        UUID deviceId = UUID.randomUUID();
        DigitalTwin twin = new DigitalTwin(deviceId, Instant.now(), "TEMPERATURE_SENSOR", 21.0, "CELSIUS", Instant.now(), java.util.Map.of());
        when(registry.get(deviceId)).thenReturn(Optional.of(twin));

        Optional<DigitalTwin> result = service.getTwin(deviceId);

        assertTrue(result.isPresent());
        assertEquals(twin, result.get());
    }

    @Test
    void getTwin_unknownDevice_returnsEmpty() {
        UUID deviceId = UUID.randomUUID();
        when(registry.get(deviceId)).thenReturn(Optional.empty());

        assertTrue(service.getTwin(deviceId).isEmpty());
    }

    @Test
    void getTwinCapabilities_returnsEveryCapabilityForDevice() {
        UUID deviceId = UUID.randomUUID();
        DigitalTwin temperature = new DigitalTwin(deviceId, Instant.now(), "TEMPERATURE", 21.0, "CELSIUS", Instant.now(), Map.of());
        DigitalTwin humidity = new DigitalTwin(deviceId, Instant.now(), "HUMIDITY", 55.0, "PERCENT", Instant.now(), Map.of());
        when(registry.getAllForDevice(deviceId)).thenReturn(List.of(temperature, humidity));

        List<DigitalTwin> result = service.getTwinCapabilities(deviceId);

        assertEquals(List.of(temperature, humidity), result);
        verify(registry).getAllForDevice(deviceId);
    }

    @Test
    void getAllTwins_delegatesToRegistryListAll() {
        DigitalTwin twin1 = new DigitalTwin(UUID.randomUUID(), Instant.now(), "TEMPERATURE_SENSOR", 1.0, "CELSIUS", Instant.now(), java.util.Map.of());
        DigitalTwin twin2 = new DigitalTwin(UUID.randomUUID(), Instant.now(), "GAS_SENSOR", 2.0, "PPM", Instant.now(), java.util.Map.of());
        when(registry.listAll()).thenReturn(List.of(twin1, twin2));

        List<DigitalTwin> result = service.getAllTwins();

        assertEquals(2, result.size());
        assertTrue(result.containsAll(List.of(twin1, twin2)));
    }

    // ==================================================================
    // Phase 11: multi-capability correctness - updateTwin() must scope its
    // out-of-order decision to the SAME real sensorType, never to the
    // device as a whole, so one capability's reading can never suppress
    // or be confused with another's.
    // ==================================================================

    @Test
    void updateTwin_differentSensorTypesForSameDevice_eachScopedIndependently() {
        UUID deviceId = UUID.randomUUID();
        Instant t1 = Instant.now();
        Instant t2 = t1.plusSeconds(1);
        Instant t3 = t1.plusSeconds(2);

        // No prior state for any of the three capabilities.
        when(registry.get(deviceId, "TEMPERATURE")).thenReturn(Optional.empty());
        when(registry.get(deviceId, "HUMIDITY")).thenReturn(Optional.empty());
        when(registry.get(deviceId, "PRESSURE")).thenReturn(Optional.empty());

        service.updateTwin(new TelemetryRequest(deviceId, t1, "TEMPERATURE", 25.0, "CELSIUS"), "corr-temp");
        service.updateTwin(new TelemetryRequest(deviceId, t2, "HUMIDITY", 60.0, "PERCENT"), "corr-hum");
        service.updateTwin(new TelemetryRequest(deviceId, t3, "PRESSURE", 1012.0, "HPA"), "corr-pres");

        // Each update queried its OWN sensorType, never another's.
        verify(registry).get(deviceId, "TEMPERATURE");
        verify(registry).get(deviceId, "HUMIDITY");
        verify(registry).get(deviceId, "PRESSURE");
        verify(registry, never()).get(eq(deviceId), eq("GAS"));

        // All three were persisted - none was rejected as "out of order"
        // merely because a different capability had already reported for
        // this device.
        ArgumentCaptor<DigitalTwin> twinCaptor = ArgumentCaptor.forClass(DigitalTwin.class);
        verify(registry, times(3)).put(twinCaptor.capture());
        List<DigitalTwin> persisted = twinCaptor.getAllValues();
        assertTrue(persisted.stream().anyMatch(t -> t.latestSensorType().equals("TEMPERATURE") && t.latestValue() == 25.0));
        assertTrue(persisted.stream().anyMatch(t -> t.latestSensorType().equals("HUMIDITY") && t.latestValue() == 60.0));
        assertTrue(persisted.stream().anyMatch(t -> t.latestSensorType().equals("PRESSURE") && t.latestValue() == 1012.0));
    }

    @Test
    void updateTwin_olderTemperatureAfterNewerHumidity_temperatureStillAccepted() {
        // The crux of the Phase 11 fix: a HUMIDITY reading newer than an
        // about-to-arrive TEMPERATURE reading must never cause that
        // TEMPERATURE reading to be rejected as "out of order" - they are
        // independent capabilities, not a shared device-level timeline.
        UUID deviceId = UUID.randomUUID();
        Instant humidityTimestamp = Instant.now();
        Instant temperatureTimestamp = humidityTimestamp.minusSeconds(10); // earlier than HUMIDITY, but TEMPERATURE has no prior state of its own

        when(registry.get(deviceId, "HUMIDITY")).thenReturn(Optional.empty());
        service.updateTwin(new TelemetryRequest(deviceId, humidityTimestamp, "HUMIDITY", 60.0, "PERCENT"), "corr-hum");

        when(registry.get(deviceId, "TEMPERATURE")).thenReturn(Optional.empty());
        service.updateTwin(new TelemetryRequest(deviceId, temperatureTimestamp, "TEMPERATURE", 25.0, "CELSIUS"), "corr-temp");

        ArgumentCaptor<DigitalTwin> twinCaptor = ArgumentCaptor.forClass(DigitalTwin.class);
        verify(registry, times(2)).put(twinCaptor.capture());
        assertTrue(twinCaptor.getAllValues().stream().anyMatch(t -> t.latestSensorType().equals("TEMPERATURE") && t.latestValue() == 25.0),
                "TEMPERATURE must be accepted - it was never compared against HUMIDITY's timestamp");
    }

    // ==================================================================
    // Tier 3 Phase 5: Digital Twin concurrency correctness.
    //
    // A Mockito mock cannot deterministically coordinate two threads
    // mid-call, since it has no real state and no hook points inside a
    // call. These tests instead use InstrumentedFakeRegistry: a real,
    // non-mocked DigitalTwinRegistry backed by a genuine ConcurrentHashMap
    // (mirroring InMemoryDigitalTwinRegistry's own cache, minus the DB
    // call, which is already covered separately against a real TimescaleDB
    // instance by InMemoryDigitalTwinRegistryTest). It exposes onGet/onPut
    // hooks so a test can observe and coordinate exactly when each thread
    // enters/exits the get()/put() calls DigitalTwinService.updateTwin()
    // makes - without touching or bypassing updateTwin() itself. Every
    // test below drives the REAL DigitalTwinService.updateTwin() method.
    // ==================================================================

    static class InstrumentedFakeRegistry implements DigitalTwinRegistry {
        private final ConcurrentHashMap<UUID, DigitalTwin> store = new ConcurrentHashMap<>();
        volatile Consumer<UUID> onGet = id -> { };
        volatile Consumer<DigitalTwin> onPut = twin -> { };

        void seed(DigitalTwin twin) {
            store.put(twin.deviceId(), twin);
        }

        @Override
        public void put(DigitalTwin twin) {
            store.put(twin.deviceId(), twin);
            onPut.accept(twin);
        }

        @Override
        public Optional<DigitalTwin> get(UUID deviceId) {
            return Optional.ofNullable(store.get(deviceId));
        }

        @Override
        public Optional<DigitalTwin> get(UUID deviceId, String sensorType) {
            // DigitalTwinService.updateTwin() calls this per-sensorType
            // overload for its out-of-order decision (Phase 11) - the
            // onGet hook must fire here, not the device-only overload
            // above, so these concurrency tests still observe exactly
            // when updateTwin() enters its critical section. Every test
            // in this class uses a single, consistent sensorType per
            // device, so filtering by it is behaviorally identical to the
            // old device-only lookup.
            Optional<DigitalTwin> result = Optional.ofNullable(store.get(deviceId))
                    .filter(twin -> twin.latestSensorType().equals(sensorType));
            onGet.accept(deviceId);
            return result;
        }

        @Override
        public List<DigitalTwin> getAllForDevice(UUID deviceId) {
            DigitalTwin twin = store.get(deviceId);
            return twin == null ? List.of() : List.of(twin);
        }

        @Override
        public void remove(UUID deviceId) {
            store.remove(deviceId);
        }

        @Override
        public List<DigitalTwin> listAll() {
            return new ArrayList<>(store.values());
        }
    }

    private TelemetryRequest telemetryFor(UUID deviceId, Instant timestamp, double value) {
        return new TelemetryRequest(deviceId, timestamp, "TEMPERATURE_SENSOR", value, "CELSIUS");
    }

    /**
     * TEST 1 - concurrent different timestamps.
     *
     * Two concurrent updates for the SAME device, t2 > t1 > base, released
     * simultaneously via a CyclicBarrier to force genuine contention on the
     * per-device lock. An AtomicBoolean-based occupancy detector brackets
     * exactly the get()-to-put() span (the critical section
     * DigitalTwinService.updateTwin() now locks per device): the SECOND
     * thread to call get() will find the flag already true if - and only
     * if - the two critical sections ever overlapped in wall-clock time.
     * This is a hard, non-probabilistic detector (a failed
     * compareAndSet proves an actual overlap occurred, it does not merely
     * suggest one might have) - exactly the kind of overlap the old
     * unlocked get-then-put sequence was capable of, and which the new
     * per-device lock must make impossible.
     *
     * The final-state assertion (must equal t2, the newer update)
     * intentionally does not depend on which thread happens to win the
     * race to run first: if t1 runs first, t2 correctly overwrites it
     * afterward; if t2 runs first, t1 is correctly self-rejected once it
     * re-reads the now-current (already newer) state under the lock -
     * this second outcome is exactly the failure mode the old, unguarded
     * code was vulnerable to (an older update winning because it read a
     * stale snapshot before the newer one committed), and is exactly what
     * per-device locking prevents.
     */
    @Test
    void concurrentUpdates_differentTimestamps_neverOverlap_andFinalStateIsTheNewerOne() throws Exception {
        UUID deviceId = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(60);
        Instant t1 = base.plusSeconds(10);
        Instant t2 = base.plusSeconds(20);

        InstrumentedFakeRegistry realRegistry = new InstrumentedFakeRegistry();
        realRegistry.seed(new DigitalTwin(deviceId, base, "TEMPERATURE_SENSOR", 0.0, "CELSIUS", base, Map.of()));
        DigitalTwinService realService = new DigitalTwinService(realRegistry, eventPublisher, realtimePublisher, pipelineService);

        AtomicBoolean occupied = new AtomicBoolean(false);
        AtomicInteger overlapCount = new AtomicInteger(0);
        realRegistry.onGet = id -> {
            if (!occupied.compareAndSet(false, true)) {
                overlapCount.incrementAndGet();
            }
        };
        realRegistry.onPut = twin -> occupied.set(false);

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                startBarrier.await();
                realService.updateTwin(telemetryFor(deviceId, t1, 10.0), "corr-t1");
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                startBarrier.await();
                realService.updateTwin(telemetryFor(deviceId, t2, 20.0), "corr-t2");
                return null;
            });
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertEquals(0, overlapCount.get(), "the two updates' critical sections must never overlap for the same device");

        DigitalTwin finalTwin = realRegistry.get(deviceId).orElseThrow();
        assertEquals(t2, finalTwin.latestTimestamp(), "final state must be the newer update, regardless of which thread ran first");
        assertEquals(20.0, finalTwin.latestValue());
    }

    /**
     * TEST 2 - concurrent equal timestamps.
     *
     * Same device, identical timestamp, two concurrent updates. The strict
     * isAfter() check means at most one of them may be accepted - but
     * without the per-device lock, both could independently read the same
     * prior (older) state and both pass their own isAfter() check against
     * it, letting both writes through. With the lock, whichever runs
     * second observes the first one's already-committed state (now
     * carrying the SAME timestamp being offered), so its own isAfter()
     * check correctly fails.
     */
    @Test
    void concurrentUpdates_equalTimestamps_onlyOneIsAccepted() throws Exception {
        UUID deviceId = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(60);
        Instant sharedTimestamp = base.plusSeconds(10);

        InstrumentedFakeRegistry realRegistry = new InstrumentedFakeRegistry();
        realRegistry.seed(new DigitalTwin(deviceId, base, "TEMPERATURE_SENSOR", 0.0, "CELSIUS", base, Map.of()));
        DigitalTwinService realService = new DigitalTwinService(realRegistry, eventPublisher, realtimePublisher, pipelineService);

        AtomicBoolean occupied = new AtomicBoolean(false);
        AtomicInteger overlapCount = new AtomicInteger(0);
        AtomicInteger putCount = new AtomicInteger(0);
        realRegistry.onGet = id -> {
            if (!occupied.compareAndSet(false, true)) {
                overlapCount.incrementAndGet();
            }
        };
        realRegistry.onPut = twin -> {
            putCount.incrementAndGet();
            occupied.set(false);
        };

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                startBarrier.await();
                realService.updateTwin(telemetryFor(deviceId, sharedTimestamp, 11.0), "corr-a");
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                startBarrier.await();
                realService.updateTwin(telemetryFor(deviceId, sharedTimestamp, 22.0), "corr-b");
                return null;
            });
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertEquals(0, overlapCount.get(), "critical sections must never overlap even for identical timestamps");
        assertEquals(1, putCount.get(), "exactly one of the two identical-timestamp concurrent updates must be accepted");

        DigitalTwin finalTwin = realRegistry.get(deviceId).orElseThrow();
        assertEquals(sharedTimestamp, finalTwin.latestTimestamp());
        assertTrue(finalTwin.latestValue() == 11.0 || finalTwin.latestValue() == 22.0,
                "final state must represent exactly the one accepted update, whichever it was");
    }

    /**
     * TEST 3 - cross-device isolation.
     *
     * Two different deviceIds, concurrent updates, proving the per-device
     * locks are genuinely independent rather than a disguised global lock.
     * Both threads rendezvous on a shared CyclicBarrier(2) from INSIDE
     * their respective get() calls (i.e., while each is expected to be
     * holding its OWN device's lock). If the two devices shared a single
     * lock, only one thread could ever be inside its critical section at a
     * time, so the second would still be blocked trying to acquire the
     * (shared) lock and could never reach the barrier - the barrier would
     * then time out. If the locks are correctly independent, both threads
     * reach the barrier while genuinely concurrently inside their own
     * critical sections, and the barrier completes without timing out.
     * This is a deterministic proof of independence, not a timing guess.
     */
    @Test
    void concurrentUpdates_differentDevices_useIndependentLocks() throws Exception {
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(60);

        InstrumentedFakeRegistry realRegistry = new InstrumentedFakeRegistry();
        realRegistry.seed(new DigitalTwin(deviceA, base, "TEMPERATURE_SENSOR", 0.0, "CELSIUS", base, Map.of()));
        realRegistry.seed(new DigitalTwin(deviceB, base, "GAS_SENSOR", 0.0, "PPM", base, Map.of()));
        DigitalTwinService realService = new DigitalTwinService(realRegistry, eventPublisher, realtimePublisher, pipelineService);

        CyclicBarrier bothInsideCriticalSection = new CyclicBarrier(2);
        realRegistry.onGet = id -> {
            try {
                bothInsideCriticalSection.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(
                        "the two devices' critical sections never overlapped - locks are NOT independent (or a deadlock occurred)", e);
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fA = pool.submit(() -> {
                realService.updateTwin(telemetryFor(deviceA, base.plusSeconds(10), 15.0), "corr-a");
                return null;
            });
            Future<?> fB = pool.submit(() -> {
                realService.updateTwin(telemetryFor(deviceB, base.plusSeconds(10), 250.0), "corr-b");
                return null;
            });
            fA.get(10, TimeUnit.SECONDS);
            fB.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        // Both updateTwin() calls have already completed above; clear the
        // barrier hook before reading final state, since InstrumentedFakeRegistry
        // fires onGet for every get() call, including these read-only
        // assertions, and no third party will ever arrive to complete
        // another round of a 2-party barrier.
        realRegistry.onGet = id -> { };

        assertEquals(15.0, realRegistry.get(deviceA).orElseThrow().latestValue());
        assertEquals(250.0, realRegistry.get(deviceB).orElseThrow().latestValue());
    }

}
