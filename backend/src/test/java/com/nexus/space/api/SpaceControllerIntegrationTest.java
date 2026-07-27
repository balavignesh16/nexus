package com.nexus.space.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.dto.CreateSpaceRequest;
import com.nexus.space.dto.SpaceResponse;
import com.nexus.space.dto.UpdateSpaceRequest;
import com.nexus.space.persistence.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SpaceControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

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
    private SpaceRepository spaceRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private SiteRepository siteRepository;

    private Building testBuilding;

    @BeforeEach
    void setUp() {
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = new Site(UUID.randomUUID(), "Test Site", "Desc", Instant.now(), Instant.now());
        Site savedSite = siteRepository.save(site);
        
        Building building = new Building(UUID.randomUUID(), savedSite, "Test Building", "Desc", Instant.now(), Instant.now());
        testBuilding = buildingRepository.save(building);
    }

    @Test
    void testCreateSpace_ValidRequest_Returns201() throws Exception {
        CreateSpaceRequest request = new CreateSpaceRequest("Test Space", "Desc");

        MvcResult result = mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Space"))
                .andExpect(jsonPath("$.buildingId").value(testBuilding.getId().toString()))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn();
                
        SpaceResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), SpaceResponse.class);
        assertThat(spaceRepository.findById(response.id())).isPresent();
    }

    @Test
    void testCreateSpace_NonexistentBuilding_Returns404() throws Exception {
        UUID badBuildingId = UUID.randomUUID();
        CreateSpaceRequest request = new CreateSpaceRequest("Test Space", "Desc");

        mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", badBuildingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void testCreateSpace_BlankName_Returns400() throws Exception {
        CreateSpaceRequest request = new CreateSpaceRequest("", "Desc");

        mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Space name cannot be blank"));
    }

    @Test
    void testCreateSpace_OversizedName_Returns400() throws Exception {
        String hugeName = "a".repeat(101);
        CreateSpaceRequest request = new CreateSpaceRequest(hugeName, "Desc");

        mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void testGetSpacesByBuildingId_Returns200() throws Exception {
        mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSpaceRequest("S1", null))));
        mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSpaceRequest("S2", null))));

        mockMvc.perform(get("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").exists());
    }
    
    @Test
    void testGetSpacesByBuildingId_NonexistentBuilding_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/buildings/{buildingId}/spaces", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetSpace_ExistingId_Returns200() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSpaceRequest("S1", null))))
                .andReturn();
        SpaceResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), SpaceResponse.class);

        mockMvc.perform(get("/api/v1/spaces/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("S1"));
    }

    @Test
    void testGetSpace_NonexistentId_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetSpace_MalformedId_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpdateSpace_ExistingSpace_Returns200_AndChangesUpdatedAt() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSpaceRequest("Old", "Old"))))
                .andReturn();
        SpaceResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), SpaceResponse.class);

        Thread.sleep(50); // delay for timestamp change

        UpdateSpaceRequest updateRequest = new UpdateSpaceRequest("New", "New");
        
        MvcResult updateResult = mockMvc.perform(put("/api/v1/spaces/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New"))
                .andReturn();
                
        SpaceResponse updated = objectMapper.readValue(updateResult.getResponse().getContentAsString(), SpaceResponse.class);
        
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfter(created.updatedAt());
    }

    @Test
    void testUpdateSpace_NonexistentSpace_Returns404() throws Exception {
        UpdateSpaceRequest updateRequest = new UpdateSpaceRequest("New", "New");

        mockMvc.perform(put("/api/v1/spaces/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteSpace_ExistingSpace_Returns204() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", testBuilding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSpaceRequest("S1", null))))
                .andReturn();
        SpaceResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), SpaceResponse.class);

        mockMvc.perform(delete("/api/v1/spaces/{id}", created.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/spaces/{id}", created.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteSpace_NonexistentSpace_Returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/spaces/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
