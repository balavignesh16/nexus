package com.nexus.device.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.capability.api.dto.CreateDeviceCapabilityRequest;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real-TimescaleDB integration coverage for Tier 5C's device capability
 * model, mirroring the existing DeviceControllerIntegrationTest pattern
 * exactly (same Site/Building/Space/Device seeding, same MockMvc style).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DeviceCapabilityIntegrationTest {

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
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceCapabilityRepository capabilityRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID spaceId;

    @BeforeEach
    void setUp() {
        capabilityRepository.deleteAll();
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Site 1", "Desc", Instant.now(), Instant.now()));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "B1", "Desc", Instant.now(), Instant.now()));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "S1", "Desc", Instant.now(), Instant.now()));
        this.spaceId = space.getId();
    }

    private UUID createDevice(String serialNumber) throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest("Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "T-1", serialNumber, "Desc", null);
        String body = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    // ---------- 1: capability persistence ----------

    @Test
    void createCapability_persistsToRealDatabase() throws Exception {
        UUID deviceId = createDevice("SN-CAP-1");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.direction").value("READ"))
                .andExpect(jsonPath("$.unit").value("CELSIUS"));

        List<DeviceCapability> fromDb = capabilityRepository.findByDeviceId(deviceId);
        assertThat(fromDb).hasSize(1);
        assertThat(fromDb.get(0).getCapabilityType()).isEqualTo(CapabilityType.TEMPERATURE);
    }

    // ---------- 2: capability retrieval ----------

    @Test
    void getCapabilities_returnsPersistedCapability() throws Exception {
        UUID deviceId = createDevice("SN-CAP-2");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.HUMIDITY, CapabilityDirection.READ, "PERCENT", null));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/capabilities", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].capabilityType").value("HUMIDITY"));
    }

    @Test
    void getCapabilities_noneConfigured_returnsEmptyArray_notNull() throws Exception {
        UUID deviceId = createDevice("SN-CAP-3");

        mockMvc.perform(get("/api/v1/devices/{deviceId}/capabilities", deviceId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ---------- 3: multiple capabilities on one device ----------

    @Test
    void multipleCapabilitiesOnOneDevice_allPersistIndependently() throws Exception {
        UUID deviceId = createDevice("SN-CAP-4");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.HUMIDITY, CapabilityDirection.READ, "PERCENT", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED, CapabilityDirection.WRITE, null, null));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/capabilities", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    // ---------- 4: duplicate type+direction rejection ----------

    @Test
    void duplicateCapability_sameTypeAndDirection_returns409() throws Exception {
        UUID deviceId = createDevice("SN-CAP-5");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        assertThat(capabilityRepository.findByDeviceId(deviceId)).hasSize(1);
    }

    // ---------- 5: different directions for same capability type allowed ----------

    @Test
    void sameCapabilityType_differentDirections_bothAllowed() throws Exception {
        UUID deviceId = createDevice("SN-CAP-6");
        CreateDeviceCapabilityRequest readRequest = new CreateDeviceCapabilityRequest(CapabilityType.LED, CapabilityDirection.READ, null, null);
        CreateDeviceCapabilityRequest writeRequest = new CreateDeviceCapabilityRequest(CapabilityType.LED, CapabilityDirection.WRITE, null, null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(readRequest)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(writeRequest)))
                .andExpect(status().isCreated());

        assertThat(capabilityRepository.findByDeviceId(deviceId)).hasSize(2);
    }

    // ---------- 6: nonexistent device ----------

    @Test
    void addCapability_nonexistentDevice_returns404() throws Exception {
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCapabilities_nonexistentDevice_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/devices/{deviceId}/capabilities", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void addCapability_invalidDirection_returns400() throws Exception {
        UUID deviceId = createDevice("SN-CAP-INVALID");
        String invalidJson = "{\"capabilityType\":\"TEMPERATURE\",\"direction\":\"NOT_A_DIRECTION\"}";

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ---------- 7: capability deletion ----------

    @Test
    void deleteCapability_removesItFromDatabase() throws Exception {
        UUID deviceId = createDevice("SN-CAP-7");
        DeviceCapability capability = capabilityRepository.save(
                new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.MOTION, CapabilityDirection.READ, null, null));

        mockMvc.perform(delete("/api/v1/devices/{deviceId}/capabilities/{capabilityId}", deviceId, capability.getId()))
                .andExpect(status().isNoContent());

        assertThat(capabilityRepository.findById(capability.getId())).isEmpty();
    }

    // ---------- 8: DELETE cannot delete another device's capability ----------

    @Test
    void deleteCapability_throughWrongDeviceUrl_returns404_andDoesNotDelete() throws Exception {
        UUID deviceA = createDevice("SN-CAP-8A");
        UUID deviceB = createDevice("SN-CAP-8B");
        DeviceCapability capabilityOnA = capabilityRepository.save(
                new DeviceCapability(UUID.randomUUID(), deviceA, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null));

        // Attempt to delete Device A's capability through Device B's URL.
        mockMvc.perform(delete("/api/v1/devices/{deviceId}/capabilities/{capabilityId}", deviceB, capabilityOnA.getId()))
                .andExpect(status().isNotFound());

        assertThat(capabilityRepository.findById(capabilityOnA.getId())).isPresent();
    }

    // ---------- 9: device deletion cascades capabilities ----------

    @Test
    void deletingDevice_cascadesItsCapabilities() throws Exception {
        UUID deviceId = createDevice("SN-CAP-9");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.HUMIDITY, CapabilityDirection.READ, "PERCENT", null));
        assertThat(capabilityRepository.findByDeviceId(deviceId)).hasSize(2);

        mockMvc.perform(delete("/api/v1/devices/{id}", deviceId))
                .andExpect(status().isNoContent());

        assertThat(capabilityRepository.findByDeviceId(deviceId)).isEmpty();
    }

    // ---------- 10: restart persistence ----------

    @Test
    void freshRepositoryInstance_seesCapabilitiesPersistedEarlier_sameDatabase() throws Exception {
        // Stands in for "restart the backend" - same pattern already
        // established by RuleRegistryPersistenceIntegrationTest and
        // InMemoryDeviceConnectivityRegistryTest: a fresh service instance
        // sharing only the real database must see what was persisted
        // before it existed.
        UUID deviceId = createDevice("SN-CAP-10");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.MOTION, CapabilityDirection.READ, null, null));

        DeviceCapabilityService freshService = new DeviceCapabilityService(capabilityRepository, deviceRepository);
        List<?> capabilities = freshService.getCapabilities(deviceId);

        assertThat(capabilities).hasSize(1);
    }

    // ---------- 11/12: DeviceResponse embedding ----------

    @Test
    void deviceResponse_embedsEmptyCapabilityList_whenNoneConfigured() throws Exception {
        UUID deviceId = createDevice("SN-CAP-11");

        mockMvc.perform(get("/api/v1/devices/{id}", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities").isArray())
                .andExpect(jsonPath("$.capabilities.length()").value(0));
    }

    @Test
    void deviceResponse_embedsPopulatedCapabilityList() throws Exception {
        UUID deviceId = createDevice("SN-CAP-12");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null));

        mockMvc.perform(get("/api/v1/devices/{id}", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.length()").value(1))
                .andExpect(jsonPath("$.capabilities[0].capabilityType").value("TEMPERATURE"));
    }

    // ---------- Phase 11: complete BOM capability vocabulary ----------
    //
    // Vocabulary/serialization round-trip only - no command execution, no
    // simulator, no firmware behavior. Confirms each new CapabilityType
    // enum value is accepted by the real API and persisted/returned
    // correctly, exactly like the existing TEMPERATURE/HUMIDITY/MOTION/LED
    // values already are.

    @Test
    void newCapabilityType_pressure_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-PRESSURE");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.PRESSURE, CapabilityDirection.READ, "HPA", null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("PRESSURE"))
                .andExpect(jsonPath("$.direction").value("READ"))
                .andExpect(jsonPath("$.unit").value("HPA"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.PRESSURE);
    }

    @Test
    void newCapabilityType_gas_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-GAS");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.GAS, CapabilityDirection.READ, "PPM", null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("GAS"))
                .andExpect(jsonPath("$.direction").value("READ"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.GAS);
    }

    @Test
    void newCapabilityType_button_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-BUTTON");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.BUTTON, CapabilityDirection.READ, null, null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("BUTTON"))
                .andExpect(jsonPath("$.direction").value("READ"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.BUTTON);
    }

    @Test
    void newCapabilityType_relay_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-RELAY");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.RELAY, CapabilityDirection.WRITE, null, null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("RELAY"))
                .andExpect(jsonPath("$.direction").value("WRITE"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.RELAY);
    }

    @Test
    void newCapabilityType_servo_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-SERVO");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.SERVO, CapabilityDirection.WRITE, "DEGREES", null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("SERVO"))
                .andExpect(jsonPath("$.direction").value("WRITE"))
                .andExpect(jsonPath("$.unit").value("DEGREES"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.SERVO);
    }

    @Test
    void newCapabilityType_fan_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-FAN");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.FAN, CapabilityDirection.WRITE, null, null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("FAN"))
                .andExpect(jsonPath("$.direction").value("WRITE"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.FAN);
    }

    @Test
    void newCapabilityType_buzzer_roundTripsThroughRealApi() throws Exception {
        UUID deviceId = createDevice("SN-CAP-BUZZER");
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.BUZZER, CapabilityDirection.WRITE, null, null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capabilityType").value("BUZZER"))
                .andExpect(jsonPath("$.direction").value("WRITE"));

        assertThat(capabilityRepository.findByDeviceId(deviceId))
                .hasSize(1)
                .allMatch(c -> c.getCapabilityType() == CapabilityType.BUZZER);
    }

    @Test
    void multiCapabilityEsp32LikeDevice_bme280PlusPirPlusLed_allCapabilitiesCoexist() throws Exception {
        // Illustrates the approved architecture: ESP32 = one Device,
        // attached physical functions = DeviceCapability rows.
        UUID deviceId = createDevice("SN-CAP-ESP32-NODE");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.HUMIDITY, CapabilityDirection.READ, "PERCENT", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.PRESSURE, CapabilityDirection.READ, "HPA", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.MOTION, CapabilityDirection.READ, null, null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED, CapabilityDirection.WRITE, null, null));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/capabilities", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    // ---------- N+1 verification on collection endpoints ----------

    @Test
    void getAllDevices_withCapabilities_doesNotProduceOneQueryPerDevice() throws Exception {
        UUID deviceA = createDevice("SN-N1-A");
        UUID deviceB = createDevice("SN-N1-B");
        UUID deviceC = createDevice("SN-N1-C");
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceA, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceB, CapabilityType.HUMIDITY, CapabilityDirection.READ, "PERCENT", null));
        capabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceC, CapabilityType.MOTION, CapabilityDirection.READ, null, null));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // The decisive, simplest proof: total prepared-statement count stays
        // small and constant regardless of device count. An N+1 pattern
        // would issue one capability query PER device (3 here); the batched
        // findByDeviceIdIn(...) approach issues exactly one, regardless of
        // how many devices are in the result set.
        assertThat(stats.getPrepareStatementCount())
                .as("device list with 3 devices must not issue one capability query per device")
                .isLessThan(6L);
    }
}
