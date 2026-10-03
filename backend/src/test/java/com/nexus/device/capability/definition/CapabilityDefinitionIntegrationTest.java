package com.nexus.device.capability.definition;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CapabilityDefinitionIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired CapabilityDefinitionRepository repository;

    @Test void emptyDatabaseMigratesSeedsAndExposesDefinitions() throws Exception {
        repository.findByKey("soil_moisture").ifPresent(repository::delete);
        mvc.perform(get("/api/v1/capability-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(11)))
                .andExpect(jsonPath("$[?(@.key == 'temperature')]").exists())
                .andExpect(jsonPath("$[?(@.key == 'relay')]").exists());
        org.junit.jupiter.api.Assertions.assertEquals(11, repository.count());
    }

    @Test void createsArbitraryStringKeyAndRejectsDuplicate() throws Exception {
        String body = """
                {"key":"soil_moisture","displayName":"Soil Moisture","direction":"READ",
                 "valueType":"NUMBER","unit":"PERCENT","allowedUnits":["PERCENT"],
                 "minimum":0,"maximum":100,"enumValues":[],"supportedCommands":[]}
                """;
        mvc.perform(post("/api/v1/capability-definitions")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("soil_moisture"));
        mvc.perform(post("/api/v1/capability-definitions")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test void rejectsInvalidKeyAndInvertedBounds() throws Exception {
        mvc.perform(post("/api/v1/capability-definitions").contentType("application/json")
                        .content("""
                            {"key":"Soil Moisture","displayName":"Soil","direction":"READ",
                             "valueType":"NUMBER","supportedCommands":[]}
                            """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/capability-definitions").contentType("application/json")
                        .content("""
                            {"key":"bad_range","displayName":"Bad","direction":"READ",
                             "valueType":"NUMBER","minimum":10,"maximum":1,"supportedCommands":[]}
                            """))
                .andExpect(status().isBadRequest());
    }
}
