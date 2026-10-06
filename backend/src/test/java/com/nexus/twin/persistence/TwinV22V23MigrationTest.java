package com.nexus.twin.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TwinV22V23MigrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Test void populatedV21CanonicalizesAliasesArchivesLosersAndPreservesUnknownReceiptTime() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .target("21").load().migrate();
        UUID site=UUID.randomUUID(),building=UUID.randomUUID(),space=UUID.randomUUID(),device=UUID.randomUUID();
        UUID binding=UUID.randomUUID();
        try(var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var sql=db.createStatement()){
            sql.executeUpdate("INSERT INTO sites(id,name) VALUES ('"+site+"','Existing')");
            sql.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES ('"+building+"','"+site+"','Existing')");
            sql.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES ('"+space+"','"+building+"','Existing',NOW(),NOW())");
            sql.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode) VALUES ('"
                    +device+"','"+space+"','Existing','CUSTOM','NEXUS','M1','P22-EXISTING','ACTIVE','SIMULATED')");
            var definition=sql.executeQuery("SELECT id FROM capability_definitions WHERE capability_key='temperature'");
            assertTrue(definition.next()); UUID definitionId=(UUID)definition.getObject(1); definition.close();
            sql.executeUpdate("INSERT INTO device_capability_bindings(id,device_id,capability_definition_id,enabled,unit,configuration,external_identifiers,created_at,updated_at) VALUES ('"
                    +binding+"','"+device+"','"+definitionId+"',TRUE,'CELSIUS','{}','{\"telemetryKey\":\"TEMP_A\"}',NOW(),NOW())");
            sql.executeUpdate(twin(device,"2026-10-06T00:00:00Z","TEMP_A",10,"2026-10-06T00:00:01Z",definitionId,binding,"temperature"));
            sql.executeUpdate(twin(device,"2026-10-06T00:01:00Z","TEMP_B",20,"2026-10-06T00:01:01Z",definitionId,binding,"temperature"));
            sql.executeUpdate(legacyTwin(device,"2026-10-06T00:00:00Z","GAS_SENSOR",100,"2026-10-06T00:00:01Z"));
            sql.executeUpdate(legacyTwin(device,"2026-10-06T00:01:00Z","GAS",200,"2026-10-06T00:01:01Z"));
        }

        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .load().migrate();

        try(var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var sql=db.createStatement()){
            var rows=sql.executeQuery("SELECT state_key,latest_value,last_wire_identifier,received_at FROM digital_twins WHERE device_id='"+device+"' ORDER BY state_key");
            assertTrue(rows.next()); assertEquals("binding:"+binding,rows.getString(1)); assertEquals(20,rows.getDouble(2));
            assertEquals("TEMP_B",rows.getString(3)); assertNull(rows.getTimestamp(4));
            assertTrue(rows.next()); assertEquals("legacy:GAS",rows.getString(1)); assertEquals(200,rows.getDouble(2));
            assertEquals("GAS",rows.getString(3)); assertNull(rows.getTimestamp(4)); assertFalse(rows.next()); rows.close();
            rows=sql.executeQuery("SELECT losing_external_identifier,original_row FROM digital_twin_alias_reconciliation_audit WHERE device_id='"+device+"' ORDER BY losing_external_identifier");
            assertTrue(rows.next()); assertEquals("GAS_SENSOR",rows.getString(1)); assertTrue(rows.getString(2).contains("GAS_SENSOR"));
            assertTrue(rows.next()); assertEquals("TEMP_A",rows.getString(1)); assertTrue(rows.getString(2).contains("TEMP_A"));
            assertFalse(rows.next()); rows.close();
            assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE digital_twins SET state_key='wrong' WHERE device_id='"+device+"'"));
            assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE device_capability_bindings SET freshness_threshold_seconds=0 WHERE id='"+binding+"'"));
        }
    }

    private static String twin(UUID device,String eventTime,String sensor,double value,String seen,UUID definition,UUID binding,String key){
        return "INSERT INTO digital_twins(device_id,latest_timestamp,latest_sensor_type,latest_value,latest_unit,last_seen,metadata,capability_definition_id,binding_id,capability_key,value_type,numeric_value,resolution_source) VALUES ('"
                +device+"','"+eventTime+"','"+sensor+"',"+value+",'CELSIUS','"+seen+"','{}','"+definition+"','"+binding+"','"+key+"','NUMBER',"+value+",'GENERIC_BINDING')";
    }
    private static String legacyTwin(UUID device,String eventTime,String sensor,double value,String seen){
        return "INSERT INTO digital_twins(device_id,latest_timestamp,latest_sensor_type,latest_value,latest_unit,last_seen,metadata) VALUES ('"
                +device+"','"+eventTime+"','"+sensor+"',"+value+",'PPM','"+seen+"','{}')";
    }
}
