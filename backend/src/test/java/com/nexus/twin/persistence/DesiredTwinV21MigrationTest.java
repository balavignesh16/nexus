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
class DesiredTwinV21MigrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Test void populatedV20UpgradesWithoutRewritingReportedOrActionRowsAndEnforcesIdentity() throws Exception {
        var base = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        base.target("14").load().migrate();
        UUID site=UUID.randomUUID(), building=UUID.randomUUID(), space=UUID.randomUUID();
        UUID device=UUID.randomUUID(), ledBinding=UUID.randomUUID();
        try (var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
             var sql=db.createStatement()) {
            sql.executeUpdate("INSERT INTO sites(id,name) VALUES ('"+site+"','Existing')");
            sql.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES ('"+building+"','"+site+"','Existing')");
            sql.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES ('"+space+"','"+building+"','Existing',NOW(),NOW())");
            sql.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode) VALUES ('"
                    +device+"','"+space+"','Existing','CUSTOM','NEXUS','M1','P21-EXISTING','ACTIVE','SIMULATED')");
            sql.executeUpdate("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit) VALUES ('"
                    +ledBinding+"','"+device+"','LED','WRITE','BOOLEAN')");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .target("20").load().migrate();
        UUID commandA=UUID.randomUUID(), commandB=UUID.randomUUID();
        try (var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
             var sql=db.createStatement()) {
            sql.executeUpdate("INSERT INTO digital_twins(device_id,latest_timestamp,latest_sensor_type,latest_value,latest_unit,last_seen,metadata) VALUES ('"
                    +device+"',NOW(),'TEMPERATURE',23.5,'CELSIUS',NOW(),'{}')");
            for (UUID command : new UUID[]{commandA,commandB}) sql.executeUpdate(
                    "INSERT INTO action_history(request_id,device_id,action_type,status,timestamp) VALUES ('"
                    +command+"','"+device+"','SEND_COMMAND','SUCCESS',NOW())");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .load().migrate();
        try (var db=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
             var sql=db.createStatement()) {
            var rows=sql.executeQuery("SELECT latest_value FROM digital_twins WHERE device_id='"+device+"'");
            assertTrue(rows.next()); assertEquals(23.5, rows.getDouble(1)); rows.close();
            String row="INSERT INTO current_desired_twin_state(device_id,target_key,binding_id,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) VALUES ('"
                    +device+"','binding:"+ledBinding+"','"+ledBinding+"','', 'led','BOOLEAN',TRUE,'MANUAL_COMMAND','"+commandA+"',NOW(),NOW())";
            sql.executeUpdate(row);
            assertThrows(SQLException.class, () -> sql.executeUpdate(row));
            assertThrows(SQLException.class, () -> sql.executeUpdate(row.replace("'"+ledBinding+"','',", "NULL,'',")));
            sql.executeUpdate("INSERT INTO current_desired_twin_state(device_id,target_key,legacy_capability_type,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) VALUES ('"
                    +device+"','legacy:LED','LED','', 'led','BOOLEAN',FALSE,'MANUAL_COMMAND','"+commandB+"',NOW(),NOW())");
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    "INSERT INTO current_desired_twin_state(device_id,target_key,legacy_capability_type,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) VALUES ('"
                    +device+"','legacy:LED','LED','', 'led','BOOLEAN',TRUE,'MANUAL_COMMAND','"+commandA+"',NOW(),NOW())"));
            for (int channel : new int[]{1,2}) sql.executeUpdate(
                    "INSERT INTO current_desired_twin_state(device_id,target_key,binding_id,subtarget_key,capability_key,value_type,object_value,source,command_id,requested_at,accepted_for_dispatch_at) VALUES ('"
                    +device+"','binding:"+ledBinding+"','"+ledBinding+"','channel:"+channel+"','relay','OBJECT','{\"channel\":"+channel+",\"state\":true}','MANUAL_COMMAND','"+commandB+"',NOW(),NOW())");
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    "INSERT INTO current_desired_twin_state(device_id,target_key,binding_id,subtarget_key,capability_key,value_type,object_value,source,command_id,requested_at,accepted_for_dispatch_at) VALUES ('"
                    +device+"','binding:"+ledBinding+"','"+ledBinding+"','channel:1','relay','OBJECT','{\"channel\":1,\"state\":false}','MANUAL_COMMAND','"+commandA+"',NOW(),NOW())"));
            rows=sql.executeQuery("SELECT COUNT(*) FROM current_desired_twin_state WHERE device_id='"+device+"'");
            assertTrue(rows.next()); assertEquals(4,rows.getInt(1));
        }
    }
}
