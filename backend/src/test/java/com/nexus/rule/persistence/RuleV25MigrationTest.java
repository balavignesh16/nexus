package com.nexus.rule.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RuleV25MigrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Test void populatedImportAndDatabaseHistoryProtection() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("24").load().migrate();
        UUID enabled=UUID.randomUUID(),disabled=UUID.randomUUID(),match=UUID.randomUUID(),orphan=UUID.randomUUID();
        UUID site=UUID.randomUUID(),building=UUID.randomUUID(),space=UUID.randomUUID(),scope=UUID.randomUUID(),target=UUID.randomUUID(),crossTarget=UUID.randomUUID();
        try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var sql=connection.createStatement()){
            sql.executeUpdate("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions) VALUES('"+enabled+"','Enabled',true,1,'[{\"field\":\"VALUE\",\"operator\":\"EQ\",\"expectedValue\":\"0\"}]','[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{}}]'),('"+disabled+"','Disabled',false,2,'[{\"field\":\"EVENT_TYPE\",\"operator\":\"EQ\",\"expectedValue\":\"TWIN_UPDATED\"}]','[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{}}]')");
            sql.executeUpdate("INSERT INTO sites(id,name) VALUES('"+site+"','Migration fixture')");
            sql.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES('"+building+"','"+site+"','Migration fixture')");
            sql.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES('"+space+"','"+building+"','Migration fixture',now(),now())");
            for(UUID device:java.util.List.of(scope,target,crossTarget))sql.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES('"+device+"','"+space+"','Fixture','CUSTOM','NEXUS','TEST','"+device+"','ACTIVE','SIMULATED',now(),now(),'test','test')");
            for(UUID device:java.util.List.of(target,crossTarget))sql.executeUpdate("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(gen_random_uuid(),'"+device+"','LED','WRITE','BOOLEAN','{}')");
            sql.executeUpdate("INSERT INTO device_capability_bindings(id,device_id,capability_definition_id,enabled) SELECT gen_random_uuid(),'"+target+"',id,false FROM capability_definitions WHERE capability_key='led'");
            String scoped="[{\"field\":\"DEVICE_ID\",\"operator\":\"EQ\",\"expectedValue\":\""+scope+"\"}]";
            String missing="[{\"field\":\"DEVICE_ID\",\"operator\":\"EQ\",\"expectedValue\":\""+scope+"\"},{\"field\":\"VALUE\",\"operator\":\"GT\",\"expectedValue\":\"35\",\"capabilityType\":\"TEMPERATURE\"}]";
            String ambiguous="[{\"field\":\"DEVICE_ID\",\"operator\":\"NE\",\"expectedValue\":\""+scope+"\"}]";
            String alert="[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{}}]";
            try(var insert=connection.prepareStatement("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,cooldown_seconds) VALUES(?,?,true,1,?::jsonb,?::jsonb,30)")){
                String[][] fixtures={{"Missing installed legacy",missing,alert},{"Ambiguous scope",ambiguous,alert},{"Disabled generic target",scoped,"[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\""+target+"\",\"capabilityKey\":\"led\",\"command\":\"SET_STATE\",\"value\":false}}]"},{"Cross-device legacy target",scoped,"[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\""+crossTarget+"\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":false}}]"}};
                for(String[] fixture:fixtures){insert.setObject(1,UUID.randomUUID());insert.setString(2,fixture[0]);insert.setString(3,fixture[1]);insert.setString(4,fixture[2]);insert.executeUpdate();}
            }
            sql.executeUpdate("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,evaluation_evidence,matched_at) VALUES('"+match+"','"+orphan+"','{\"name\":\"old\"}','"+UUID.randomUUID()+"','{}',now())");
        }
        var flyway=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load();
        flyway.migrate();flyway.validate();
        try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var sql=connection.createStatement()){
            var rows=sql.executeQuery("SELECT lifecycle,enabled,original_enabled,reconciliation,active_version_id,working_version_id,execution_epoch FROM rules WHERE rule_id='"+enabled+"'");
            assertTrue(rows.next());assertEquals("PAUSED",rows.getString(1));assertFalse(rows.getBoolean(2));assertTrue(rows.getBoolean(3));assertEquals("PENDING",rows.getString(4));assertNull(rows.getObject(5));assertNotNull(rows.getObject(6));assertEquals(0,rows.getLong(7));rows.close();
            rows=sql.executeQuery("SELECT count(*) FROM rules");assertTrue(rows.next());assertEquals(6,rows.getInt(1));rows.close();
            rows=sql.executeQuery("SELECT count(*) FROM rule_versions");assertTrue(rows.next());assertEquals(6,rows.getInt(1));rows.close();
            rows=sql.executeQuery("SELECT count(*) FROM rules WHERE lifecycle='PAUSED' AND reconciliation='PENDING' AND active_version_id IS NULL AND enabled=false");assertTrue(rows.next());assertEquals(6,rows.getInt(1));rows.close();
            rows=sql.executeQuery("SELECT count(*) FROM rules r JOIN rule_versions v ON v.version_id=r.working_version_id WHERE v.definition->'conditions'=r.conditions AND v.definition->'actions'=r.actions AND v.definition->'cooldownSeconds'=COALESCE(to_jsonb(r.cooldown_seconds),'null'::jsonb)");assertTrue(rows.next());assertEquals(6,rows.getInt(1));rows.close();
            rows=sql.executeQuery("SELECT rule_version_id,rule_revision,execution_epoch,rule_snapshot::text FROM rule_matches WHERE match_id='"+match+"'");assertTrue(rows.next());assertNull(rows.getObject(1));assertNull(rows.getObject(2));assertNull(rows.getObject(3));assertTrue(rows.getString(4).contains("old"));rows.close();
            assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE rule_versions SET revision=2"));
            assertThrows(SQLException.class,()->sql.executeUpdate("DELETE FROM rule_versions"));
            assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE rules SET active_version_id=(SELECT working_version_id FROM rules WHERE rule_id='"+disabled+"') WHERE rule_id='"+enabled+"'"));
            assertThrows(SQLException.class,()->sql.executeUpdate("INSERT INTO rule_versions(version_id,rule_id,revision,definition,created_at,provenance) VALUES(gen_random_uuid(),'"+enabled+"',1,'{}',now(),'TEST')"));
            rows=sql.executeQuery("SELECT count(*) FROM rule_validation_attempts");assertTrue(rows.next());assertEquals(0,rows.getInt(1));
        }
    }
    @Test void failedV25RollsBackSchemaAndImportWithoutFlywayRepair() throws Exception {
        try(var broken=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"))){
            broken.start();var config=Flyway.configure().dataSource(broken.getJdbcUrl(),broken.getUsername(),broken.getPassword());config.target("24").load().migrate();
            try(var connection=DriverManager.getConnection(broken.getJdbcUrl(),broken.getUsername(),broken.getPassword());var sql=connection.createStatement()){
                sql.executeUpdate("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions) VALUES(gen_random_uuid(),'Rollback witness',true,1,'[]','[]')");
                sql.execute("ALTER TABLE rules RENAME COLUMN cooldown_seconds TO fixture_missing_cooldown");
                assertThrows(org.flywaydb.core.api.FlywayException.class,()->Flyway.configure().dataSource(broken.getJdbcUrl(),broken.getUsername(),broken.getPassword()).load().migrate());
                var rows=sql.executeQuery("SELECT to_regclass('rule_versions')");assertTrue(rows.next());assertNull(rows.getObject(1));rows.close();
                rows=sql.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name='rules' AND column_name='lifecycle'");assertTrue(rows.next());assertEquals(0,rows.getInt(1));rows.close();
                rows=sql.executeQuery("SELECT enabled FROM rules WHERE name='Rollback witness'");assertTrue(rows.next());assertTrue(rows.getBoolean(1));rows.close();
                rows=sql.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version='25'");assertTrue(rows.next());assertEquals(0,rows.getInt(1));rows.close();
                sql.execute("ALTER TABLE rules RENAME COLUMN fixture_missing_cooldown TO cooldown_seconds");
            }
            var recovered=Flyway.configure().dataSource(broken.getJdbcUrl(),broken.getUsername(),broken.getPassword()).load();recovered.migrate();recovered.validate();
        }
    }
}
