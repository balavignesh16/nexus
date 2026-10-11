package com.nexus.twin.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TwinV24MigrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @Test void populatedV23PreservesFactsAndInitializesOldIntentConservatively() throws Exception {
        var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        config.target("23").load().migrate();
        UUID site=UUID.randomUUID(),building=UUID.randomUUID(),space=UUID.randomUUID(),device=UUID.randomUUID();
        UUID command=UUID.randomUUID(),transition=UUID.randomUUID();
        try(var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var sql=db.createStatement()){
            sql.executeUpdate("INSERT INTO sites(id,name) VALUES ('"+site+"','Existing')");
            sql.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES ('"+building+"','"+site+"','Existing')");
            sql.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES ('"+space+"','"+building+"','Existing',NOW(),NOW())");
            sql.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode) VALUES ('"
                    +device+"','"+space+"','Existing','CUSTOM','NEXUS','M1','P24-EXISTING','ACTIVE','SIMULATED')");
            sql.executeUpdate("INSERT INTO action_history(request_id,device_id,action_type,status,timestamp,ack_status,ack_received_at) VALUES ('"
                    +command+"','"+device+"','SEND_COMMAND','SUCCESS',NOW(),'ACCEPTED','2026-10-01T10:00:00Z')");
            sql.executeUpdate("INSERT INTO current_desired_twin_state(device_id,target_key,legacy_capability_type,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) VALUES ('"
                    +device+"','legacy:LED','LED','','led','BOOLEAN',TRUE,'MANUAL_COMMAND','"+command+"',NOW(),NOW())");
            sql.executeUpdate("INSERT INTO digital_twins(device_id,state_key,legacy_capability_type,latest_timestamp,latest_sensor_type,latest_value,latest_unit,last_seen,metadata,received_at) VALUES ('"
                    +device+"','legacy:LED','LED',NOW(),'LED',1,'BOOLEAN',NOW(),'{}',NOW())");
            sql.executeUpdate("INSERT INTO twin_state_transitions(transition_id,device_id,state_key,legacy_capability_type,transition_type,source,value_type,transitioned_at,command_id) VALUES ('"
                    +transition+"','"+device+"','legacy:LED','LED','DESIRED_STATE_CHANGED','MANUAL_COMMAND','BOOLEAN',NOW(),'"+command+"')");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        try(var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var sql=db.createStatement()){
            var rows=sql.executeQuery("SELECT verification_status,verification_deadline_at,mapping_id,discrepancy_reason FROM current_twin_verification_state WHERE device_id='"+device+"'");
            assertTrue(rows.next());assertEquals("UNVERIFIED",rows.getString(1));assertNull(rows.getTimestamp(2));
            assertNull(rows.getObject(3));assertEquals("MIGRATED_WITHOUT_VERIFICATION_BASELINE",rows.getString(4));
            assertFalse(rows.next());rows.close();
            rows=sql.executeQuery("SELECT ack_received_at,ack_backend_received_at,ack_device_timestamp FROM action_history WHERE request_id='"+command+"'");
            assertTrue(rows.next());assertEquals("2026-10-01T10:00:00Z",rows.getTimestamp(1).toInstant().toString());
            assertNull(rows.getTimestamp(2));assertNull(rows.getTimestamp(3));rows.close();
            rows=sql.executeQuery("SELECT latest_value FROM digital_twins WHERE device_id='"+device+"'");
            assertTrue(rows.next());assertEquals(1,rows.getDouble(1));rows.close();
            rows=sql.executeQuery("SELECT transition_type FROM twin_state_transitions WHERE transition_id='"+transition+"'");
            assertTrue(rows.next());assertEquals("DESIRED_STATE_CHANGED",rows.getString(1));rows.close();
        }
    }
}
