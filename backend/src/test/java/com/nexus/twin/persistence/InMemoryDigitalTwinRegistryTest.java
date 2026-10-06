package com.nexus.twin.persistence;

import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinId;
import com.nexus.twin.domain.DigitalTwinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises InMemoryDigitalTwinRegistry against a real, freshly-provisioned
 * TimescaleDB instance (not H2) to verify write-through persistence and
 * startup hydration - the two behaviors that can only be genuinely proven
 * against a real database, not a mock.
 *
 * Each test constructs its own InMemoryDigitalTwinRegistry instance (rather
 * than using the Spring-managed singleton bean) so that tests are isolated
 * from each other's in-memory cache state; DigitalTwinRegistry exposes no
 * cache-clearing method, so reusing the shared bean across test methods
 * would leak cache entries between tests. The real DigitalTwinRepository
 * bean (backed by the Testcontainers database) is shared and cleaned per test.
 */
@SpringBootTest
@Testcontainers
class InMemoryDigitalTwinRegistryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private DigitalTwinRepository digitalTwinRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private SiteRepository siteRepository;

    private Device device;
    private Device secondDevice;

    @BeforeEach
    void setUp() {
        // digital_twins.device_id has a real FK to devices(id); a valid device
        // row must exist before a twin can be persisted.
        digitalTwinRepository.deleteAll();
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now()));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "B1", "Desc", Instant.now(), Instant.now()));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "S1", "Desc", Instant.now(), Instant.now()));

        device = new Device(UUID.randomUUID(), space, "Device A", DeviceType.TEMPERATURE_SENSOR, "M", "M", "SN-1", "D", com.nexus.device.domain.DeviceMode.SIMULATED);
        device.update("Device A", DeviceStatus.ACTIVE, "D");
        device = deviceRepository.save(device);

        secondDevice = new Device(UUID.randomUUID(), space, "Device B", DeviceType.GAS_SENSOR, "M", "M", "SN-2", "D", com.nexus.device.domain.DeviceMode.SIMULATED);
        secondDevice.update("Device B", DeviceStatus.ACTIVE, "D");
        secondDevice = deviceRepository.save(secondDevice);
    }

    private static final String SENSOR_TYPE = "TEMPERATURE_SENSOR";

    private DigitalTwin twinFor(UUID deviceId, Instant timestamp, double value) {
        return new DigitalTwin(deviceId, timestamp, SENSOR_TYPE, value, "CELSIUS", timestamp, Map.of());
    }

    private DigitalTwinId idFor(UUID deviceId) {
        return new DigitalTwinId(deviceId, "legacy:TEMPERATURE");
    }

    @Test
    void put_persistsToRealDatabaseAndUpdatesCache() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        // TIMESTAMP WITH TIME ZONE round-trips at microsecond precision; Instant.now()
        // can carry nanoseconds, so truncate before persisting to allow an exact
        // equality check after the real database round trip.
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        DigitalTwin twin = twinFor(device.getId(), now, 23.5);

        registry.put(twin);

        // Bypass the cache entirely - query the real repository directly.
        Optional<DigitalTwin> fromDb = digitalTwinRepository.findById(idFor(device.getId()));
        assertTrue(fromDb.isPresent(), "twin must be persisted to the real database");
        assertEquals(23.5, fromDb.get().latestValue());
        assertEquals(now, fromDb.get().latestTimestamp());

        // Cache must also reflect it.
        Optional<DigitalTwin> fromCache = registry.get(device.getId());
        assertTrue(fromCache.isPresent(), "twin must also be readable from the cache");
        assertEquals(23.5, fromCache.get().latestValue());
    }

    @Test
    void get_returnsEmptyForDeviceWithNoTwin() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);

        assertTrue(registry.get(UUID.randomUUID()).isEmpty());
    }

    @Test
    void hydration_freshRegistryLoadsPersistedTwinsFromDatabase() {
        // Simulate one backend instance persisting a twin...
        InMemoryDigitalTwinRegistry writerRegistry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        DigitalTwin twin = twinFor(device.getId(), Instant.now(), 30.0);
        writerRegistry.put(twin);

        // ...then a fresh registry instance (standing in for a restarted
        // application, sharing only the real database) hydrating from it.
        InMemoryDigitalTwinRegistry freshRegistry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        assertTrue(freshRegistry.get(device.getId()).isEmpty(),
                "a brand new registry must start with an empty cache, before init() runs");

        freshRegistry.init();

        Optional<DigitalTwin> hydrated = freshRegistry.get(device.getId());
        assertTrue(hydrated.isPresent(), "startup hydration must load the persisted twin into the cache");
        assertEquals(30.0, hydrated.get().latestValue());
        assertEquals(device.getId(), hydrated.get().deviceId());
    }

    @Test
    void hydration_withNoPersistedTwins_resultsInEmptyCache() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);

        registry.init();

        assertTrue(registry.listAll().isEmpty());
    }

    @Test
    void remove_deletesFromBothCacheAndDatabase() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        DigitalTwin twin = twinFor(device.getId(), Instant.now(), 18.0);
        registry.put(twin);
        assertTrue(registry.get(device.getId()).isPresent());

        registry.remove(device.getId());

        assertTrue(registry.get(device.getId()).isEmpty(), "twin must be gone from the cache");
        assertTrue(digitalTwinRepository.findById(idFor(device.getId())).isEmpty(), "twin must be gone from the database");
    }

    @Test
    void listAll_returnsEveryTwinPutIntoThisRegistryInstance() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        DigitalTwin twin1 = twinFor(device.getId(), Instant.now(), 10.0);
        DigitalTwin twin2 = twinFor(secondDevice.getId(), Instant.now(), 500.0);

        registry.put(twin1);
        registry.put(twin2);

        List<DigitalTwin> all = registry.listAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(t -> t.deviceId().equals(device.getId())));
        assertTrue(all.stream().anyMatch(t -> t.deviceId().equals(secondDevice.getId())));
    }

    @Test
    void put_overwritingExistingTwin_updatesBothCacheAndDatabaseToLatestValue() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        registry.put(twinFor(device.getId(), Instant.now(), 10.0));

        Instant later = Instant.now().plusSeconds(5);
        registry.put(twinFor(device.getId(), later, 99.0));

        assertEquals(99.0, registry.get(device.getId()).orElseThrow().latestValue());
        assertEquals(99.0, digitalTwinRepository.findById(idFor(device.getId())).orElseThrow().latestValue());
        // Only one row should exist: both puts share the same (device_id,
        // latest_sensor_type) composite key, so the second is a real UPDATE
        // of the same row, not a new INSERT.
        assertEquals(1, digitalTwinRepository.count());
    }

    // ---- Phase 11: multi-capability persistence correctness ----

    @Test
    void multiCapability_sequentialUpdatesForDifferentSensorTypes_retainAllRowsIndependently() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        Instant t1 = Instant.now();
        Instant t2 = t1.plusSeconds(1);
        Instant t3 = t1.plusSeconds(2);

        registry.put(new DigitalTwin(device.getId(), t1, "TEMPERATURE", 25.0, "CELSIUS", t1, Map.of()));
        registry.put(new DigitalTwin(device.getId(), t2, "HUMIDITY", 60.0, "PERCENT", t2, Map.of()));
        registry.put(new DigitalTwin(device.getId(), t3, "PRESSURE", 1012.0, "HPA", t3, Map.of()));

        // None overwrote another - all three independently retrievable.
        assertEquals(25.0, registry.get(device.getId(), "TEMPERATURE").orElseThrow().latestValue());
        assertEquals(60.0, registry.get(device.getId(), "HUMIDITY").orElseThrow().latestValue());
        assertEquals(1012.0, registry.get(device.getId(), "PRESSURE").orElseThrow().latestValue());

        // Real persistence, not just cache.
        assertEquals(25.0, digitalTwinRepository.findById(new DigitalTwinId(device.getId(), "legacy:TEMPERATURE")).orElseThrow().latestValue());
        assertEquals(60.0, digitalTwinRepository.findById(new DigitalTwinId(device.getId(), "legacy:HUMIDITY")).orElseThrow().latestValue());
        assertEquals(1012.0, digitalTwinRepository.findById(new DigitalTwinId(device.getId(), "legacy:PRESSURE")).orElseThrow().latestValue());

        // The device-level (no sensorType) lookup returns the most recent
        // across all capabilities - PRESSURE, since it was updated last.
        DigitalTwin representative = registry.get(device.getId()).orElseThrow();
        assertEquals("PRESSURE", representative.latestSensorType());
        assertEquals(1012.0, representative.latestValue());
    }

    @Test
    void multiCapability_listAllReturnsOneRepresentativeRowPerDevice_notOnePerCapability() {
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        Instant t1 = Instant.now();
        Instant t2 = t1.plusSeconds(1);
        Instant t3 = t1.plusSeconds(2);

        // Device A: three capabilities.
        registry.put(new DigitalTwin(device.getId(), t1, "TEMPERATURE", 25.0, "CELSIUS", t1, Map.of()));
        registry.put(new DigitalTwin(device.getId(), t2, "HUMIDITY", 60.0, "PERCENT", t2, Map.of()));
        registry.put(new DigitalTwin(device.getId(), t3, "PRESSURE", 1012.0, "HPA", t3, Map.of()));
        // Device B: one capability.
        registry.put(new DigitalTwin(secondDevice.getId(), t1, "GAS_SENSOR", 400.0, "PPM", t1, Map.of()));

        List<DigitalTwin> all = registry.listAll();

        assertEquals(2, all.size(), "one representative row per device, never one row per capability");
        assertTrue(all.stream().anyMatch(t -> t.deviceId().equals(device.getId()) && t.latestSensorType().equals("PRESSURE")));
        assertTrue(all.stream().anyMatch(t -> t.deviceId().equals(secondDevice.getId()) && t.latestSensorType().equals("GAS_SENSOR")));
    }

    @Test
    void multiCapability_outOfOrderProtectionIsScopedPerSensorType() {
        // This exercises the registry only (DigitalTwinService owns the
        // actual out-of-order decision); it confirms the registry gives
        // the service the real per-capability lookup it needs to make
        // that decision correctly - a newer TEMPERATURE reading must not
        // be compared against an unrelated HUMIDITY reading's timestamp.
        InMemoryDigitalTwinRegistry registry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        Instant tHumidityNewer = Instant.now();
        Instant tTemperatureOlder = tHumidityNewer.minusSeconds(10);

        registry.put(new DigitalTwin(device.getId(), tHumidityNewer, "HUMIDITY", 60.0, "PERCENT", tHumidityNewer, Map.of()));

        // A TEMPERATURE reading, timestamped before HUMIDITY's, must still
        // be visible as a genuinely new TEMPERATURE row - the two sensor
        // types occupy independent identity, not a shared device-level one.
        assertTrue(registry.get(device.getId(), "TEMPERATURE").isEmpty(), "no TEMPERATURE row exists yet");
        registry.put(new DigitalTwin(device.getId(), tTemperatureOlder, "TEMPERATURE", 25.0, "CELSIUS", tTemperatureOlder, Map.of()));
        assertEquals(25.0, registry.get(device.getId(), "TEMPERATURE").orElseThrow().latestValue());
        assertEquals(60.0, registry.get(device.getId(), "HUMIDITY").orElseThrow().latestValue());
    }

    @Test
    void multiCapability_survivesHydrationAfterRestart() {
        InMemoryDigitalTwinRegistry writerRegistry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        Instant t1 = Instant.now();
        Instant t2 = t1.plusSeconds(1);
        writerRegistry.put(new DigitalTwin(device.getId(), t1, "TEMPERATURE", 25.0, "CELSIUS", t1, Map.of()));
        writerRegistry.put(new DigitalTwin(device.getId(), t2, "HUMIDITY", 60.0, "PERCENT", t2, Map.of()));

        InMemoryDigitalTwinRegistry freshRegistry = new InMemoryDigitalTwinRegistry(digitalTwinRepository);
        freshRegistry.init();

        List<DigitalTwin> hydrated = freshRegistry.getAllForDevice(device.getId());
        assertEquals(2, hydrated.size(), "both capability rows must survive a cache-clear/reload (restart) cycle");
        assertTrue(hydrated.stream().anyMatch(t -> t.latestSensorType().equals("TEMPERATURE") && t.latestValue() == 25.0));
        assertTrue(hydrated.stream().anyMatch(t -> t.latestSensorType().equals("HUMIDITY") && t.latestValue() == 60.0));
    }
}
