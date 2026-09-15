package com.nexus.device.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DeviceControllerIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeviceRepository deviceRepository;
    
    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private DeviceConnectivityRegistry deviceConnectivityRegistry;

    private UUID spaceId;

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = new Site(UUID.randomUUID(), "Site 1", "Desc", Instant.now(), Instant.now());
        site = siteRepository.save(site);

        Building building = new Building(UUID.randomUUID(), site, "B1", "Desc", Instant.now(), Instant.now());
        building = buildingRepository.save(building);

        Space space = new Space(UUID.randomUUID(), building, "S1", "Desc", Instant.now(), Instant.now());
        space = spaceRepository.save(space);
        
        this.spaceId = space.getId();
    }

    @Test
    void getAllDevices_shouldReturn200WithSeededDevices() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.GAS_SENSOR, "Acme", "V1", "SN-ALL-1", "Desc", null
        );
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].name").value("Sensor"))
                .andExpect(jsonPath("$[0].deviceType").value("GAS_SENSOR"))
                .andExpect(jsonPath("$[0].status").value("REGISTERED"))
                .andExpect(jsonPath("$[0].serialNumber").value("SN-ALL-1"));
    }

    @Test
    void getAllDevices_shouldReturnEmptyArray_whenNoDevicesExist() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createDevice_shouldReturn201() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-1", "Desc", null
        );

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Sensor"))
                .andExpect(jsonPath("$.serialNumber").value("SN-1"))
                .andExpect(jsonPath("$.createdBy").value("system"));
    }

    // ---------- Phase 11: DeviceMode backward-compatible contract ----------

    @Test
    void createDevice_modeOmitted_defaultsToSimulated() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-MODE-OMITTED", "Desc", null
        );

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SIMULATED"));
    }

    @Test
    void createDevice_modeExplicitlySimulated_isPreserved() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-MODE-SIM", "Desc", DeviceMode.SIMULATED
        );

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SIMULATED"));
    }

    @Test
    void createDevice_modeExplicitlyReal_isPreserved() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.CUSTOM, "Acme", "V1", "SN-MODE-REAL", "Desc", DeviceMode.REAL
        );

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("REAL"));
    }

    @Test
    void createDevice_invalidMode_returns400() throws Exception {
        String body = "{\"name\":\"Sensor\",\"deviceType\":\"CUSTOM\",\"manufacturer\":\"Acme\",\"model\":\"V1\"," +
                "\"serialNumber\":\"SN-MODE-INVALID\",\"description\":\"Desc\",\"mode\":\"NOT_A_REAL_MODE\"}";

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDevice_afterCreationWithoutMode_stillReportsSimulatedOnGet() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-MODE-GET", "Desc", null
        );
        String createResponse = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/devices/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SIMULATED"));
    }

    @Test
    void createDevice_shouldReturn409_whenSerialNumberDuplicate() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-1", "Desc", null
        );
        
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void deleteDevice_shouldReturn204() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-1", "Desc", null
        );
        String response = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(delete("/api/v1/devices/{id}", id))
                .andExpect(status().isNoContent());

        assertThat(deviceRepository.existsById(UUID.fromString(id))).isFalse();
    }

    // ---------- Tier 5D: POST /devices/{deviceId}/commands endpoint contract ----------
    //
    // This test class has no Mosquitto broker configured - MQTTConfiguration's
    // startup is non-fatal without one (an existing, already-proven Tier 3
    // guarantee), so mqttClient.isConnected() simply returns false here. That
    // makes this the right place to test the endpoint's own contract (404,
    // 400) without needing a real broker; the full authorized/online publish
    // path is proven separately in CommandDispatchMqttIntegrationTest, which
    // does provision a real Mosquitto container.

    @Test
    void sendDeviceCommand_unknownDevice_returns404() throws Exception {
        String body = "{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}";

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendDeviceCommand_missingRequiredField_returns400() throws Exception {
        CreateDeviceRequest deviceRequest = new CreateDeviceRequest(
                "Sensor", DeviceType.CUSTOM, "Acme", "V1", "SN-CMD-400", "Desc", null);
        String deviceResponse = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deviceRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String deviceId = objectMapper.readTree(deviceResponse).get("id").asText();

        // Missing required "value" field.
        String invalidBody = "{\"capability\":\"LED\",\"command\":\"SET_STATE\"}";

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendDeviceCommand_authorizedDevice_withoutBroker_returnsTruthfulFailure_never200Success() throws Exception {
        // No MQTT broker is configured in this test class - the command
        // must be honestly reported as FAILURE (never a fabricated
        // SUCCESS), even though the REST call itself succeeds (201).
        CreateDeviceRequest deviceRequest = new CreateDeviceRequest(
                "Sensor", DeviceType.CUSTOM, "Acme", "V1", "SN-CMD-NOBROKER", "Desc", null);
        String deviceResponse = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deviceRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String deviceId = objectMapper.readTree(deviceResponse).get("id").asText();

        String body = "{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}";

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ---------- Device connectivity ----------

    @Test
    void getDeviceConnectivity_neverObserved_returnsTruthfulUnknown_notFabricatedOnlineOrOffline() throws Exception {
        CreateDeviceRequest deviceRequest = new CreateDeviceRequest(
                "Sensor", DeviceType.CUSTOM, "Acme", "V1", "SN-CONN-UNKNOWN", "Desc", null);
        String deviceResponse = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deviceRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String deviceId = objectMapper.readTree(deviceResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/devices/{deviceId}/connectivity", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.lastChangedAt").doesNotExist());
    }

    @Test
    void getDeviceConnectivity_observedOnline_returnsTruthfulOnlineWithTimestamp() throws Exception {
        CreateDeviceRequest deviceRequest = new CreateDeviceRequest(
                "Sensor", DeviceType.CUSTOM, "Acme", "V1", "SN-CONN-ONLINE", "Desc", null);
        String deviceResponse = mockMvc.perform(post("/api/v1/spaces/{spaceId}/devices", spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deviceRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID deviceId = UUID.fromString(objectMapper.readTree(deviceResponse).get("id").asText());

        deviceConnectivityRegistry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        mockMvc.perform(get("/api/v1/devices/{deviceId}/connectivity", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.lastChangedAt").exists());
    }

    @Test
    void getDeviceConnectivity_unknownDevice_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/devices/{deviceId}/connectivity", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
