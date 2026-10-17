package com.nexus.action.outbox;

import com.nexus.device.application.DeviceService;
import com.nexus.device.domain.exception.DeviceDeletionBlockedException;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application side of F1: deleting a device through the service and through the REST API is refused with a typed 409
 * while the device has delivery obligations or unpurged delivery records, and nothing is changed by the refusal.
 */
@SpringBootTest(properties = "nexus.command.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class CommandOutboxDeviceDeletionApiIntegrationTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired DeviceService deviceService;
    @Autowired CommandOutboxRepository outbox;

    private UUID deviceWithRow(String state) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            UUID device = OutboxSql.device(c);
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            if (state.equals("PUBLISHED")) {
                OutboxSql.update(c, command, "state = 'IN_FLIGHT', attempt_count = 1, next_attempt_at = NULL, lease_owner = 'w', lease_expires_at = NOW() + INTERVAL '6 seconds'");
                OutboxSql.update(c, command, "state = 'PUBLISHED', lease_owner = NULL, lease_expires_at = NULL, terminal_at = NOW(), first_publish_confirmed_at = NOW()");
            }
            return device;
        }
    }

    @Test
    void theServiceRefusesWhileAnObligationIsUnresolvedAndNothingChanges() throws Exception {
        UUID device = deviceWithRow("QUEUED");
        DeviceDeletionBlockedException e = assertThrows(DeviceDeletionBlockedException.class, () -> deviceService.deleteDevice(device));
        assertEquals(DeviceDeletionBlockedException.UNRESOLVED_COMMANDS, e.getCode());
        assertEquals(device, e.getDeviceId());
        assertEquals(1, outbox.countByState(OutboxState.QUEUED) >= 1 ? 1 : 0);
        try (Connection c = dataSource.getConnection()) {
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE device_id = '" + device + "' AND state = 'QUEUED'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE device_id = '" + device + "'"));
        }
    }

    @Test
    void theRestApiAnswers409WithAStableCodeForAnUnresolvedObligation() throws Exception {
        UUID device = deviceWithRow("QUEUED");
        mockMvc.perform(delete("/api/v1/devices/{id}", device))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.code").value("DEVICE_HAS_UNRESOLVED_COMMANDS"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        try (Connection c = dataSource.getConnection()) {
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"));
        }
    }

    @Test
    void resolvedDeliveryRecordsAreRefusedWithTheirOwnCodeUntilRetentionPurgesThem() throws Exception {
        UUID device = deviceWithRow("PUBLISHED");
        mockMvc.perform(delete("/api/v1/devices/{id}", device))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE"));
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            assertEquals(1, s.executeUpdate("DELETE FROM command_outbox WHERE device_id = '" + device + "'"));   // what the retention purge does
        }
        mockMvc.perform(delete("/api/v1/devices/{id}", device)).andExpect(status().isNoContent());
    }

    @Test
    void aDeviceWithoutDeliveryRecordsStillDeletesNormally() throws Exception {
        UUID device;
        try (Connection c = dataSource.getConnection()) {
            device = OutboxSql.device(c);
        }
        mockMvc.perform(delete("/api/v1/devices/{id}", device)).andExpect(status().isNoContent());
    }

    @Test
    void anUnknownDeviceIsStillNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/devices/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
