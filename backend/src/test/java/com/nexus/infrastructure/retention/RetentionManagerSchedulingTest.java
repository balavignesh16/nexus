package com.nexus.infrastructure.retention;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RetentionManagerSchedulingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nexus_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void retentionCleanupJobIsRegisteredAsScheduledTask() {
        Set<ScheduledTask> scheduledTasks = scheduledTaskHolder.getScheduledTasks();

        boolean retentionTaskRegistered = scheduledTasks.stream()
                .anyMatch(task -> task.getTask().toString().contains("RetentionManager"));

        assertThat(retentionTaskRegistered)
                .as("RetentionManager.cleanupOldData() must be registered as a scheduled task once "
                        + "@EnableScheduling is active. If this fails, scheduling is not wired up and the "
                        + "retention job will never run.")
                .isTrue();
    }
}
