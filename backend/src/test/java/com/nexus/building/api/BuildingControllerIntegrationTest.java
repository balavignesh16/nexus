package com.nexus.building.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.building.dto.BuildingResponse;
import com.nexus.building.dto.CreateBuildingRequest;
import com.nexus.building.dto.UpdateBuildingRequest;
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
class BuildingControllerIntegrationTest {

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
    private BuildingRepository buildingRepository;

    @Autowired
    private SiteRepository siteRepository;
    
    @Autowired
    private com.nexus.space.persistence.SpaceRepository spaceRepository;

    private Site testSite;

    @BeforeEach
    void setUp() {
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = new Site(UUID.randomUUID(), "Test Site", "Desc", Instant.now(), Instant.now());
        testSite = siteRepository.save(site);
    }

    @Test
    void testCreateBuilding_ValidRequest_Returns201() throws Exception {
        CreateBuildingRequest request = new CreateBuildingRequest("Test Building", "Desc");

        MvcResult result = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Building"))
                .andExpect(jsonPath("$.siteId").value(testSite.getId().toString()))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn();
                
        BuildingResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), BuildingResponse.class);
        assertThat(buildingRepository.findById(response.id())).isPresent();
    }

    @Test
    void testCreateBuilding_NonexistentSite_Returns404() throws Exception {
        UUID badSiteId = UUID.randomUUID();
        CreateBuildingRequest request = new CreateBuildingRequest("Test Building", "Desc");

        mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", badSiteId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void testCreateBuilding_BlankName_Returns400() throws Exception {
        CreateBuildingRequest request = new CreateBuildingRequest("", "Desc");

        mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Building name cannot be blank"));
    }

    @Test
    void testCreateBuilding_OversizedName_Returns400() throws Exception {
        String hugeName = "a".repeat(101);
        CreateBuildingRequest request = new CreateBuildingRequest(hugeName, "Desc");

        mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void testGetBuildingsBySiteId_Returns200() throws Exception {
        mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("B1", null))));
        mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("B2", null))));

        mockMvc.perform(get("/api/v1/sites/{siteId}/buildings", testSite.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").exists());
    }
    
    @Test
    void testGetBuildingsBySiteId_NonexistentSite_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/sites/{siteId}/buildings", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetBuilding_ExistingId_Returns200() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("B1", null))))
                .andReturn();
        BuildingResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), BuildingResponse.class);

        mockMvc.perform(get("/api/v1/buildings/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("B1"));
    }

    @Test
    void testGetBuilding_NonexistentId_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/buildings/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetBuilding_MalformedId_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/buildings/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpdateBuilding_ExistingBuilding_Returns200_AndChangesUpdatedAt() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("Old", "Old"))))
                .andReturn();
        BuildingResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), BuildingResponse.class);

        Thread.sleep(50); // delay for timestamp change

        UpdateBuildingRequest updateRequest = new UpdateBuildingRequest("New", "New");
        
        MvcResult updateResult = mockMvc.perform(put("/api/v1/buildings/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New"))
                .andReturn();
                
        BuildingResponse updated = objectMapper.readValue(updateResult.getResponse().getContentAsString(), BuildingResponse.class);
        
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfter(created.updatedAt());
    }

    @Test
    void testUpdateBuilding_NonexistentBuilding_Returns404() throws Exception {
        UpdateBuildingRequest updateRequest = new UpdateBuildingRequest("New", "New");

        mockMvc.perform(put("/api/v1/buildings/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteBuilding_ExistingBuilding_Returns204() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("B1", null))))
                .andReturn();
        BuildingResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), BuildingResponse.class);

        mockMvc.perform(delete("/api/v1/buildings/{id}", created.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/buildings/{id}", created.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteBuilding_NonexistentBuilding_Returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/buildings/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteBuilding_WithSpaces_Returns409() throws Exception {
        MvcResult createBuildingResult = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("Building With Space", null))))
                .andReturn();
        BuildingResponse building = objectMapper.readValue(createBuildingResult.getResponse().getContentAsString(), BuildingResponse.class);

        String createSpacePayload = "{\"name\": \"Space 1\", \"description\": \"Desc\"}";
        mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", building.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createSpacePayload))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/buildings/{id}", building.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Cannot delete Building because it contains Spaces. Building ID: " + building.id()));
    }

    @Test
    void testDeleteBuilding_WithSpaces_DeletedSpaceFirst_Returns204() throws Exception {
        MvcResult createBuildingResult = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", testSite.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBuildingRequest("Building With Space To Delete", null))))
                .andReturn();
        BuildingResponse building = objectMapper.readValue(createBuildingResult.getResponse().getContentAsString(), BuildingResponse.class);

        String createSpacePayload = "{\"name\": \"Space 1\", \"description\": \"Desc\"}";
        MvcResult createSpaceResult = mockMvc.perform(post("/api/v1/buildings/{buildingId}/spaces", building.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createSpacePayload))
                .andReturn();
        
        String spaceResponseStr = createSpaceResult.getResponse().getContentAsString();
        String spaceIdStr = spaceResponseStr.split("\"id\":\"")[1].split("\"")[0];

        mockMvc.perform(delete("/api/v1/spaces/{id}", spaceIdStr))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/buildings/{id}", building.id()))
                .andExpect(status().isNoContent());
    }
}
