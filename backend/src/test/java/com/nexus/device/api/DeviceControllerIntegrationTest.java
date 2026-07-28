package com.nexus.device.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
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
    void createDevice_shouldReturn201() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-1", "Desc"
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

    @Test
    void createDevice_shouldReturn409_whenSerialNumberDuplicate() throws Exception {
        CreateDeviceRequest request = new CreateDeviceRequest(
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-1", "Desc"
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
            "Sensor", DeviceType.TEMPERATURE_SENSOR, "Acme", "V1", "SN-1", "Desc"
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
}
