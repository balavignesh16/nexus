package com.nexus.telemetry.inbox;

import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.application.DeviceService;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.device.onboarding.DeviceOnboardingSessionRepository;
import com.nexus.device.onboarding.DeviceProvisioningService;
import com.nexus.device.onboarding.OnboardingLifecycleService;
import com.nexus.device.onboarding.OnboardingState;
import com.nexus.device.onboarding.OnboardingTelemetryRejectedException;
import com.nexus.device.onboarding.api.ProvisionDeviceRequest;
import com.nexus.device.template.DeviceTemplate;
import com.nexus.device.template.DeviceTemplateCapability;
import com.nexus.device.template.DeviceTemplateCapabilityRepository;
import com.nexus.device.template.DeviceTemplateRepository;
import com.nexus.device.template.ProtocolType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inbox on the two paths that change a device's lifecycle (R17, R30; ledger P4-084): a duplicate of the first onboarding reading
 * neither advances the onboarding session nor activates the device again, an invalid first reading leaves no dedupe evidence, and
 * device deletion (service path, and racing a live ingestion) removes the entries without orphans while the existing deletion guard
 * is untouched.
 */
@SpringBootTest
@Testcontainers
class TelemetryInboxOnboardingAndDeletionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://127.0.0.1:1");
        registry.add("mqtt.client.id", () -> "p45-onboarding-" + UUID.randomUUID());
    }

    @Autowired TelemetryService telemetry;
    @Autowired DeviceService deviceService;
    @Autowired DeviceProvisioningService provisioning;
    @Autowired OnboardingLifecycleService onboarding;
    @Autowired DeviceOnboardingSessionRepository sessions;
    @Autowired DeviceRepository devices;
    @Autowired DeviceTemplateRepository templates;
    @Autowired DeviceTemplateCapabilityRepository templateCapabilities;
    @Autowired CapabilityDefinitionRepository definitions;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.telemetry.application.InboundMessageDedupe dedupeSpy;

    private static final Instant SAMPLE_TIME = Instant.parse("2026-10-17T12:00:00Z");

    private Space space() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Onb", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        return spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
    }

    /** A provisioned (REGISTERED) device whose onboarding session is waiting for its first telemetry. */
    private UUID deviceWaitingForFirstTelemetry() {
        Space space = space();
        var template = templates.save(new DeviceTemplate(UUID.randomUUID(), "p45_" + UUID.randomUUID().toString().replace("-", ""),
                "P4.5 onboarding", null, null, null, ProtocolType.MQTT, true, Map.of()));
        var temperature = definitions.findByKey("temperature").orElseThrow();
        assertEquals(CapabilityDirection.READ, temperature.getDirection());
        templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template, temperature, true, "CELSIUS", 0, null, Map.of()));
        UUID device = provisioning.provision(new ProvisionDeviceRequest("p45-" + UUID.randomUUID(), template.getId(), space.getId(),
                "Onboarding device", "P45-ONB-" + UUID.randomUUID(), DeviceMode.SIMULATED, Map.of())).deviceId();
        onboarding.observedOnline(device);
        assertEquals(OnboardingState.WAITING_FOR_TELEMETRY, sessions.findByDeviceId(device).orElseThrow().getState());
        assertEquals(DeviceStatus.REGISTERED, devices.findById(device).orElseThrow().getStatus());
        return device;
    }

    private static TelemetryRequest temperature(UUID device, double value, String messageId) {
        return new TelemetryRequest(device, SAMPLE_TIME, null, value, null, "temperature", null, messageId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---- P4-084 ----

    @Test
    void aDuplicateOfTheFirstOnboardingReadingNeitherAdvancesTheSessionNorActivatesTheDeviceAgain() throws Exception {
        UUID device = deviceWaitingForFirstTelemetry();

        assertNull(telemetry.processTelemetry(temperature(device, 21.5, "first-1"), TelemetrySource.MQTT).duplicate());
        assertEquals(DeviceStatus.ACTIVE, devices.findById(device).orElseThrow().getStatus(), "the first reading activates the device");
        var session = sessions.findByDeviceId(device).orElseThrow();
        assertEquals(OnboardingState.OPERATIONAL, session.getState());
        Instant sessionUpdated = session.getUpdatedAt(), firstTelemetryAt = session.getFirstTelemetryAt();
        Instant deviceUpdated = devices.findById(device).orElseThrow().getUpdatedAt();
        long sessionVersion = session.getVersion();
        Thread.sleep(50);

        assertTrue(telemetry.processTelemetry(temperature(device, 21.5, "first-1"), TelemetrySource.MQTT).repeatsAcceptedMessage());

        var after = sessions.findByDeviceId(device).orElseThrow();
        assertEquals(OnboardingState.OPERATIONAL, after.getState());
        assertEquals(sessionUpdated, after.getUpdatedAt(), "the session was not touched again");
        assertEquals(firstTelemetryAt, after.getFirstTelemetryAt());
        assertEquals(sessionVersion, after.getVersion());
        assertEquals(deviceUpdated, devices.findById(device).orElseThrow().getUpdatedAt(), "the device was not activated again");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(1, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device));
    }

    @Test
    void anInvalidFirstOnboardingReadingLeavesNoDedupeEvidenceSoTheSameIdCanStillOnboardTheDevice() {
        UUID device = deviceWaitingForFirstTelemetry();

        assertThrows(OnboardingTelemetryRejectedException.class,
                () -> telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME, null, "not a number", null, "temperature", null, "first-bad"), TelemetrySource.MQTT));
        assertEquals(0, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device), "a rejected reading is never recorded as seen");
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
    }

    // ---- device deletion ----

    @Test
    void deletingADeviceThroughTheServiceRemovesItsEntriesAndLeavesNoOrphan() {
        Space space = space();
        Device d = new Device(UUID.randomUUID(), space, "Del", DeviceType.CUSTOM, "NEXUS", "TEST", "P45-DEL-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("Del", DeviceStatus.ACTIVE, null);
        UUID device = devices.save(d).getId();
        telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME, "TEMPERATURE", 21.5, "CELSIUS", null, null, "del-1"), TelemetrySource.HTTP);
        assertEquals(1, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device));

        deviceService.deleteDevice(device);

        assertEquals(0, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device));
        assertEquals(0, count("SELECT COUNT(*) FROM inbound_message_dedupe d WHERE NOT EXISTS (SELECT 1 FROM devices x WHERE x.id = d.device_id)"));
    }

    @Test
    void deletionRacingALiveIngestionNeverLeavesAnOrphanedEntryOrADanglingFailure() throws Exception {
        Space space = space();
        Device d = new Device(UUID.randomUUID(), space, "Race", DeviceType.CUSTOM, "NEXUS", "TEST", "P45-RACE-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("Race", DeviceStatus.ACTIVE, null);
        UUID device = devices.save(d).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean deleted = new AtomicBoolean(false);
        Future<Integer> ingest = pool.submit(() -> {
            go.await();
            int accepted = 0;
            for (int i = 0; i < 400 && !deleted.get(); i++) {
                try {
                    telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME.plusSeconds(i), "TEMPERATURE", 20.0 + i, "CELSIUS", null, null, "race-" + i), TelemetrySource.MQTT);
                    accepted++;
                } catch (RuntimeException expectedOnceTheDeviceIsGone) {
                    // ResourceNotFoundException or a foreign-key failure: the device was deleted under the ingestion
                }
            }
            return accepted;
        });
        Future<?> delete = pool.submit(() -> {
            go.await();
            Thread.sleep(150);
            deviceService.deleteDevice(device);   // no retry: the two now serialise on the device row lock (follow-up F3 fixed), so no deadlock victim exists
            deleted.set(true);
            return null;
        });
        go.countDown();
        int accepted = ingest.get(120, TimeUnit.SECONDS);
        delete.get(120, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(accepted >= 1, "some readings were accepted before the deletion");
        assertEquals(0, count("SELECT COUNT(*) FROM devices WHERE id = ?", device));
        assertEquals(0, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device), "no dedupe entry survives the device");
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device), "no telemetry survives the device (unchanged behaviour)");
    }

    /**
     * Follow-up F3, deterministically. The ingestion is parked immediately AFTER its dedupe claim (the point where, before the fix, it held only the
     * foreign key's key-share lock) and the deletion is started meanwhile. Before the fix the deletion held the row lock and its DELETE waited for the
     * key-share holder while the ingestion then asked for the row lock: PostgreSQL aborted one with SQLSTATE 40P01. Now the ingestion takes the row lock
     * BEFORE the claim, so the deletion waits at its first lock; both complete, the ingestion first, and nothing deadlocks, retries or is swallowed.
     */
    @Test
    void aDeletionStartedWhileAnIdentifiedIngestionIsInFlightWaitsForItAndNeverDeadlocks() throws Exception {
        Space space = space();
        Device d = new Device(UUID.randomUUID(), space, "F3", DeviceType.CUSTOM, "NEXUS", "TEST", "P45-F3-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("F3", DeviceStatus.ACTIVE, null);
        UUID device = devices.save(d).getId();
        CountDownLatch claimed = new CountDownLatch(1), release = new CountDownLatch(1);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status ->
                org.mockito.Mockito.doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    claimed.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return result;
                }).when(dedupeSpy).claim(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> ingest = pool.submit(() -> telemetry.processTelemetry(
                    new TelemetryRequest(device, SAMPLE_TIME, "TEMPERATURE", 21.5, "CELSIUS", null, null, "f3-deterministic"), TelemetrySource.MQTT));
            assertTrue(claimed.await(30, TimeUnit.SECONDS));
            Future<?> delete = pool.submit(() -> deviceService.deleteDevice(device));
            Thread.sleep(800);                       // the deletion has had ample time to take its first lock and block
            assertTrue(!delete.isDone(), "the deletion must be waiting for the in-flight ingestion, not racing it");
            release.countDown();
            ingest.get(30, TimeUnit.SECONDS);        // no SQLSTATE 40P01 for either side
            delete.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            org.mockito.Mockito.reset(dedupeSpy);
        }
        assertEquals(0, count("SELECT COUNT(*) FROM devices WHERE id = ?", device));
        assertEquals(0, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device));
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(0, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", device));
        // twin_state_transitions is append-only history without a device cascade (unchanged behaviour), so it is not asserted here
    }
}
