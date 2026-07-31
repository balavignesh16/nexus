package com.nexus.site.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.site.dto.CreateSiteRequest;
import com.nexus.site.dto.SiteResponse;
import com.nexus.site.dto.UpdateSiteRequest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SiteControllerIntegrationTest {

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
    private com.nexus.building.persistence.BuildingRepository buildingRepository;

    @Autowired
    private com.nexus.space.persistence.SpaceRepository spaceRepository;

    @Autowired
    private SiteRepository siteRepository;

    @BeforeEach
    void setUp() {
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();
    }

    @Test
    void testCreateSite_ValidRequest_Returns201() throws Exception {
        CreateSiteRequest request = new CreateSiteRequest("Test Campus", "Main HQ");

        MvcResult result = mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Campus"))
                .andExpect(jsonPath("$.description").value("Main HQ"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn();
                
        SiteResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), SiteResponse.class);
        assertThat(siteRepository.findById(response.id())).isPresent();
    }

    @Test
    void testCreateSite_BlankName_Returns400() throws Exception {
        CreateSiteRequest request = new CreateSiteRequest("", "Main HQ");

        mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Site name cannot be blank"));
    }

    @Test
    void testCreateSite_OversizedName_Returns400() throws Exception {
        String hugeName = "a".repeat(101);
        CreateSiteRequest request = new CreateSiteRequest(hugeName, "Main HQ");

        mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Site name cannot exceed 100 characters"));
    }

    @Test
    void testGetAllSites_Returns200() throws Exception {
        mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Site 1", null))));
        mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Site 2", null))));

        mockMvc.perform(get("/api/v1/sites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void testGetSite_ExistingId_Returns200() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Test Campus", null))))
                .andReturn();
        SiteResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), SiteResponse.class);

        mockMvc.perform(get("/api/v1/sites/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Campus"));
    }

    @Test
    void testGetSite_NonexistentId_Returns404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/sites/{id}", nonexistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Site not found with identifier: " + nonexistentId));
    }

    @Test
    void testGetSite_MalformedId_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sites/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid UUID or parameter format."));
    }

    @Test
    void testUpdateSite_ExistingSite_Returns200_AndChangesUpdatedAt() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Old Campus", "Old"))))
                .andReturn();
        SiteResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), SiteResponse.class);

        // Small delay to ensure timestamp updates are visibly different
        Thread.sleep(50);

        UpdateSiteRequest updateRequest = new UpdateSiteRequest("New Campus", "New");
        
        MvcResult updateResult = mockMvc.perform(put("/api/v1/sites/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Campus"))
                .andExpect(jsonPath("$.description").value("New"))
                .andReturn();
                
        SiteResponse updated = objectMapper.readValue(updateResult.getResponse().getContentAsString(), SiteResponse.class);
        
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfter(created.updatedAt());
    }

    @Test
    void testUpdateSite_NonexistentSite_Returns404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();
        UpdateSiteRequest updateRequest = new UpdateSiteRequest("New Campus", "New");

        mockMvc.perform(put("/api/v1/sites/{id}", nonexistentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteSite_ExistingSite_Returns204() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Campus", null))))
                .andReturn();
        SiteResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), SiteResponse.class);

        mockMvc.perform(delete("/api/v1/sites/{id}", created.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/sites/{id}", created.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteSite_NonexistentSite_Returns404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/sites/{id}", nonexistentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteSite_WithBuildings_Returns409() throws Exception {
        // Create Site
        MvcResult createSiteResult = mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Site With Building", null))))
                .andReturn();
        SiteResponse site = objectMapper.readValue(createSiteResult.getResponse().getContentAsString(), SiteResponse.class);

        // Create Building under Site
        String createBuildingPayload = "{\"name\": \"Building 1\", \"description\": \"Desc\"}";
        mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", site.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBuildingPayload))
                .andExpect(status().isCreated());

        // Attempt to delete Site, expect 409
        mockMvc.perform(delete("/api/v1/sites/{id}", site.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Cannot delete Site because it contains Buildings. Site ID: " + site.id()));
    }

    @Test
    void testDeleteSite_WithBuildings_DeletedBuildingFirst_Returns204() throws Exception {
        // Create Site
        MvcResult createSiteResult = mockMvc.perform(post("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateSiteRequest("Site With Building To Delete", null))))
                .andReturn();
        SiteResponse site = objectMapper.readValue(createSiteResult.getResponse().getContentAsString(), SiteResponse.class);

        // Create Building under Site
        String createBuildingPayload = "{\"name\": \"Building 1\", \"description\": \"Desc\"}";
        MvcResult createBuildingResult = mockMvc.perform(post("/api/v1/sites/{siteId}/buildings", site.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBuildingPayload))
                .andReturn();
        
        // Extract Building ID (using string parsing to avoid coupling to BuildingResponse in this class)
        String buildingResponseStr = createBuildingResult.getResponse().getContentAsString();
        String buildingIdStr = buildingResponseStr.split("\"id\":\"")[1].split("\"")[0];

        // Delete Building
        mockMvc.perform(delete("/api/v1/buildings/{id}", buildingIdStr))
                .andExpect(status().isNoContent());

        // Delete Site, expect 204
        mockMvc.perform(delete("/api/v1/sites/{id}", site.id()))
                .andExpect(status().isNoContent());
    }
}
