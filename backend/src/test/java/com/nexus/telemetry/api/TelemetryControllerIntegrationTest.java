package com.nexus.telemetry.api;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import com.nexus.telemetry.api.dto.TelemetryRequest;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TelemetryControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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

    private Device activeDevice;
    private Device inactiveDevice;

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now());
        site = siteRepository.save(site);
        
        Building building = new Building(UUID.randomUUID(), site, "B1", "Desc", Instant.now(), Instant.now());
        building = buildingRepository.save(building);
        
        Space space = new Space(UUID.randomUUID(), building, "S1", "Desc", Instant.now(), Instant.now());
        space = spaceRepository.save(space);

        activeDevice = new Device(UUID.randomUUID(), space, "A", DeviceType.TEMPERATURE_SENSOR, "M", "M", "1", "D");
        activeDevice.update("A", DeviceStatus.ACTIVE, "D");
        activeDevice = deviceRepository.save(activeDevice);
        
        inactiveDevice = new Device(UUID.randomUUID(), space, "I", DeviceType.TEMPERATURE_SENSOR, "M", "M", "2", "D");
        inactiveDevice.update("I", DeviceStatus.OFFLINE, "D");
        inactiveDevice = deviceRepository.save(inactiveDevice);
    }

    @Test
    void ingestTelemetry_Success() throws Exception {
        TelemetryRequest req = new TelemetryRequest(
            activeDevice.getId(), Instant.now(), "TEMPERATURE_SENSOR", 25.0, "CELSIUS"
        );
        
        mockMvc.perform(post("/api/v1/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.value").value(25.0));
    }
    
    @Test
    void ingestTelemetry_InactiveDevice_Throws400() throws Exception {
        // Technically the GlobalExceptionHandler converts IllegalStateException/IllegalArgumentException to 400 Bad Request
        TelemetryRequest req = new TelemetryRequest(
            inactiveDevice.getId(), Instant.now(), "TEMPERATURE_SENSOR", 25.0, "CELSIUS"
        );
        
        mockMvc.perform(post("/api/v1/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
    
    @Test
    void getTelemetry_Success() throws Exception {
        TelemetryRequest req = new TelemetryRequest(
            activeDevice.getId(), Instant.now(), "TEMPERATURE_SENSOR", 25.0, "CELSIUS"
        );
        
        mockMvc.perform(post("/api/v1/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
                
        mockMvc.perform(get("/api/v1/telemetry?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value(25.0));
    }

    @Test
    void getDeviceTelemetry_Success() throws Exception {
        TelemetryRequest req = new TelemetryRequest(
                activeDevice.getId(), Instant.now(), "TEMPERATURE_SENSOR", 22.5, "CELSIUS"
        );

        mockMvc.perform(post("/api/v1/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/devices/" + activeDevice.getId() + "/telemetry?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].value").value(22.5))
                .andExpect(jsonPath("$.returnedCount").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getLatestDeviceTelemetry_Success() throws Exception {
        TelemetryRequest req = new TelemetryRequest(
                activeDevice.getId(), Instant.now(), "TEMPERATURE_SENSOR", 23.5, "CELSIUS"
        );

        mockMvc.perform(post("/api/v1/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/devices/" + activeDevice.getId() + "/telemetry/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(23.5));
    }
}
