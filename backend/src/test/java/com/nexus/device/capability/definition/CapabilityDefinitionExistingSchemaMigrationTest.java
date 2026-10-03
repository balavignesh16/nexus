package com.nexus.device.capability.definition;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class CapabilityDefinitionExistingSchemaMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Test void additiveMigrationPreservesExistingDeviceAndCapabilityRows() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("14").load().migrate();
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID();
        UUID device = UUID.randomUUID(), capability = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sites(id,name) VALUES ('" + site + "','Existing')");
            statement.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES ('" + building + "','" + site + "','Existing')");
            statement.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES ('"
                    + space + "','" + building + "','Existing',NOW(),NOW())");
            statement.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode) VALUES ('"
                    + device + "','" + space + "','Existing','CUSTOM','NEXUS','M1','EXISTING-1','ACTIVE','SIMULATED')");
            statement.executeUpdate("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit) VALUES ('"
                    + capability + "','" + device + "','TEMPERATURE','READ','CELSIUS')");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM devices WHERE id='" + device + "'")) {
                rows.next(); assertEquals(1, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM device_capabilities WHERE id='" + capability + "'")) {
                rows.next(); assertEquals(1, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM capability_definitions")) {
                rows.next(); assertEquals(11, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM device_capability_bindings WHERE id='" + capability + "' AND device_id='" + device + "'")) {
                rows.next(); assertEquals(1, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM device_templates")) {
                rows.next(); assertEquals(3, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM device_onboarding_sessions")) {
                rows.next(); assertEquals(0, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_name='telemetry_history' AND column_name='unit' AND is_nullable='YES'")) {
                rows.next(); assertEquals(1, rows.getInt(1));
            }
        }
    }
}
