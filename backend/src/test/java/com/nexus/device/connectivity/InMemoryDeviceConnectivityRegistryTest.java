package com.nexus.device.connectivity;

import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises InMemoryDeviceConnectivityRegistry against a real TimescaleDB
 * instance, directly mirroring RuleRegistryPersistenceIntegrationTest's
 * proven "fresh instance, same database" pattern as the deterministic proxy
 * for a real backend restart - the strongest available proof short of
 * actually killing and relaunching the JVM.
 */
@SpringBootTest
@Testcontainers
class InMemoryDeviceConnectivityRegistryTest {

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
    private DeviceConnectivityRepository connectivityRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private SpaceRepository spaceRepository;

    private UUID deviceId;

    @BeforeEach
    void setUp() {
        connectivityRepository.deleteAll();
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Instant now = Instant.now();
        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Test Site", "desc", now, now));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "Test Building", "desc", now, now));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "Test Space", "desc", now, now));
        Device device = deviceRepository.save(new Device(UUID.randomUUID(), space, "Test Device",
                DeviceType.TEMPERATURE_SENSOR, "Acme", "T1", "SN-" + UUID.randomUUID(), "desc"));
        deviceId = device.getId();
    }

    private InMemoryDeviceConnectivityRegistry newRegistry() {
        return new InMemoryDeviceConnectivityRegistry(connectivityRepository);
    }

    // ---------- online/offline persistence ----------

    @Test
    void applyObservedStatus_online_persistsToRealDatabase() {
        InMemoryDeviceConnectivityRegistry registry = newRegistry();

        boolean changed = registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        assertTrue(changed);
        DeviceConnectivity fromDb = connectivityRepository.findById(deviceId).orElseThrow();
        assertEquals(DeviceConnectivityStatus.ONLINE, fromDb.status());
    }

    @Test
    void applyObservedStatus_offline_persistsToRealDatabase() {
        InMemoryDeviceConnectivityRegistry registry = newRegistry();

        boolean changed = registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);

        assertTrue(changed);
        DeviceConnectivity fromDb = connectivityRepository.findById(deviceId).orElseThrow();
        assertEquals(DeviceConnectivityStatus.OFFLINE, fromDb.status());
    }

    // ---------- duplicate observation is harmless ----------

    @Test
    void duplicateOnline_doesNotChangeLastChangedAt() {
        InMemoryDeviceConnectivityRegistry registry = newRegistry();
        registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);
        var firstChangedAt = connectivityRepository.findById(deviceId).orElseThrow().lastChangedAt();

        boolean changedAgain = registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        assertFalse(changedAgain, "a duplicate observation of the already-current status must report no transition");
        var secondChangedAt = connectivityRepository.findById(deviceId).orElseThrow().lastChangedAt();
        assertEquals(firstChangedAt, secondChangedAt, "last_changed_at must not move for a harmless duplicate");
    }

    @Test
    void duplicateOffline_doesNotChangeLastChangedAt() {
        InMemoryDeviceConnectivityRegistry registry = newRegistry();
        registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);
        var firstChangedAt = connectivityRepository.findById(deviceId).orElseThrow().lastChangedAt();

        boolean changedAgain = registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);

        assertFalse(changedAgain);
        var secondChangedAt = connectivityRepository.findById(deviceId).orElseThrow().lastChangedAt();
        assertEquals(firstChangedAt, secondChangedAt);
    }

    @Test
    void transitionFromOnlineToOffline_isReportedAsAChange() {
        InMemoryDeviceConnectivityRegistry registry = newRegistry();
        registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        boolean changed = registry.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);

        assertTrue(changed, "a genuine status transition must be reported as a change");
    }

    // ---------- UNKNOWN represented by row absence ----------

    @Test
    void neverObservedDevice_hasNoRow_representedAsAbsent() {
        InMemoryDeviceConnectivityRegistry registry = newRegistry();

        Optional<DeviceConnectivity> result = registry.get(deviceId);

        assertTrue(result.isEmpty(), "a device with no observed status must be absent (UNKNOWN), never defaulted to OFFLINE");
    }

    // ---------- backend restart: fresh instance, same database ----------

    @Test
    void freshRegistryInstance_hydratesLastKnownStateFromDatabase_beforeAnyMqttActivity() {
        // Stands in for "restart the backend" - see
        // RuleRegistryPersistenceIntegrationTest.freshRegistry_loadsRulesPersistedByAPreviousRegistryInstance_sameDatabase
        // for the same, already-established pattern.
        InMemoryDeviceConnectivityRegistry instanceBeforeRestart = newRegistry();
        instanceBeforeRestart.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        InMemoryDeviceConnectivityRegistry instanceAfterRestart = newRegistry();
        assertTrue(instanceAfterRestart.get(deviceId).isEmpty(),
                "a brand new registry must start with an empty cache, before init() runs");

        instanceAfterRestart.init();

        DeviceConnectivity hydrated = instanceAfterRestart.get(deviceId).orElseThrow();
        assertEquals(DeviceConnectivityStatus.ONLINE, hydrated.status(),
                "the correct last-known connectivity state must be recovered from the database immediately at "
                        + "startup, independent of any new MQTT message having arrived yet");
    }

    @Test
    void freshRegistryInstance_hydratesOfflineStateCorrectly() {
        InMemoryDeviceConnectivityRegistry instanceBeforeRestart = newRegistry();
        instanceBeforeRestart.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);

        InMemoryDeviceConnectivityRegistry instanceAfterRestart = newRegistry();
        instanceAfterRestart.init();

        assertEquals(DeviceConnectivityStatus.OFFLINE, instanceAfterRestart.get(deviceId).orElseThrow().status());
    }
}
