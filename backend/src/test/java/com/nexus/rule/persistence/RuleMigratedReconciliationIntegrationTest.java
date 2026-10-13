package com.nexus.rule.persistence;

import com.nexus.rule.application.*;
import com.nexus.rule.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual populated V24 migration before Spring starts; no import-shaped replacement fixtures. */
@SpringBootTest @Testcontainers @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ContextConfiguration(initializers=RuleMigratedReconciliationIntegrationTest.PopulatedV24.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RuleMigratedReconciliationIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    static final Map<String,UUID> ids=new LinkedHashMap<>();static final Map<UUID,String> snapshots=new LinkedHashMap<>(),evidence=new LinkedHashMap<>();
    static boolean prepared;static Instant importStart;static UUID scope,target,oldMatch;static Object firstRegistry;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);r.add("spring.flyway.url",db::getJdbcUrl);r.add("spring.flyway.user",db::getUsername);r.add("spring.flyway.password",db::getPassword);r.add("mqtt.broker.url",()->"tcp://127.0.0.1:1");}
    // Hold imports closed for initial assertions; invoke the real worker/service explicitly below.
    @MockBean RuleImportReconciler automaticWorker;
    @MockBean java.time.Clock clock;
    static Instant evaluationTime;
    @Autowired RuleMatcher matcher;
    @Autowired RuleLifecycleService lifecycle;@Autowired RuleVersionStore versions;@Autowired RuleRegistry registry;@Autowired JdbcTemplate sql;
    @BeforeEach void controlledTime(){if(evaluationTime==null)evaluationTime=sql.queryForObject("SELECT min(created_at) FROM rule_versions",Timestamp.class).toInstant().plusSeconds(1);org.mockito.Mockito.when(clock.instant()).thenReturn(evaluationTime);}
    public static class PopulatedV24 implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(ConfigurableApplicationContext context){if(prepared)return;
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("24").load().migrate();
            try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var statement=connection.createStatement()){
                UUID site=UUID.randomUUID(),building=UUID.randomUUID(),space=UUID.randomUUID();scope=UUID.randomUUID();target=UUID.randomUUID();
                statement.executeUpdate("INSERT INTO sites(id,name) VALUES('"+site+"','Migrated')");statement.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES('"+building+"','"+site+"','Migrated')");statement.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES('"+space+"','"+building+"','Migrated',now(),now())");
                for(UUID device:List.of(scope,target))statement.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES('"+device+"','"+space+"','Migrated','CUSTOM','NEXUS','TEST','"+device+"','ACTIVE','SIMULATED',now(),now(),'test','test')");
                statement.executeUpdate("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(gen_random_uuid(),'"+scope+"','LED','WRITE','BOOLEAN','{}'),(gen_random_uuid(),'"+target+"','LED','WRITE','BOOLEAN','{}')");
                statement.executeUpdate("INSERT INTO device_capability_bindings(id,device_id,capability_definition_id,enabled) SELECT gen_random_uuid(),'"+scope+"',id,false FROM capability_definitions WHERE capability_key='led'");
                var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                for(String name:List.of("enabled","disabled","invalid_condition","invalid_action","missing_legacy","disabled_binding","ambiguous","conflicting","cross_device","pending","user_edit","user_pause","parallel")){
                    UUID id=UUID.randomUUID();ids.put(name,id);List<RuleCondition> conditions=new ArrayList<>(List.of(new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"TWIN_UPDATED")));List<RuleAction> actions=List.of(new RuleAction("CREATE_ALERT",Map.of()));
                    if(name.equals("invalid_condition"))conditions.add(new RuleCondition(RuleField.VALUE,RuleOperator.GT,"not-a-number"));
                    if(name.equals("invalid_action"))actions=List.of(new RuleAction("NO_EXECUTOR",Map.of()));
                    if(name.equals("missing_legacy")){conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,scope.toString()));conditions.add(new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",com.nexus.device.capability.CapabilityType.TEMPERATURE));}
                    if(name.equals("disabled_binding")){conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,scope.toString()));actions=List.of(new RuleAction("SEND_COMMAND",Map.of("deviceId",scope.toString(),"capabilityKey","led","command","SET_STATE","value",true)));}
                    if(name.equals("ambiguous"))conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.NE,scope.toString()));
                    if(name.equals("conflicting")){conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,scope.toString()));conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,target.toString()));}
                    if(name.equals("cross_device")){conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,scope.toString()));actions=List.of(new RuleAction("SEND_COMMAND",Map.of("deviceId",target.toString(),"capability","LED","command","SET_STATE","value",false)));}
                    try(var insert=connection.prepareStatement("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata,cooldown_seconds) VALUES(?,?,?,1,?::jsonb,?::jsonb,'{\"owner\":\"import\"}',30)")){insert.setObject(1,id);insert.setString(2,name);insert.setBoolean(3,!name.equals("disabled"));insert.setString(4,mapper.writeValueAsString(conditions));insert.setString(5,mapper.writeValueAsString(actions));insert.executeUpdate();}
                }
                for(UUID rule:List.of(ids.get("enabled"),UUID.randomUUID())){
                    UUID match=UUID.randomUUID();oldMatch=match;
                    try(var insert=connection.prepareStatement("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,evaluation_evidence,matched_at) VALUES(?,?,?::jsonb,?,?::jsonb,now())")){insert.setObject(1,match);insert.setObject(2,rule);insert.setString(3,"{\"name\":\"historical\",\"conditions\":[{\"expectedValue\":\"0\"}],\"metadata\":{\"preserve\":true}}");insert.setObject(4,UUID.randomUUID());insert.setString(5,"{\"evaluatedConditions\":[{\"actualValue\":0,\"conditionMet\":false}],\"outcome\":\"matched\"}");insert.executeUpdate();}
                    try(var rows=statement.executeQuery("SELECT rule_snapshot::text,evaluation_evidence::text FROM rule_matches WHERE match_id='"+match+"'")){assertTrue(rows.next());snapshots.put(match,rows.getString(1));evidence.put(match,rows.getString(2));}
                }
                importStart=Instant.now();prepared=true;
            }catch(Exception failure){throw new IllegalStateException("Owned V24 fixture preparation failed",failure);}
        }
    }
    private void preservedHistory(){for(UUID match:snapshots.keySet()){assertEquals(snapshots.get(match),sql.queryForObject("SELECT rule_snapshot::text FROM rule_matches WHERE match_id=?",String.class,match));assertEquals(evidence.get(match),sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE match_id=?",String.class,match));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE match_id=? AND rule_version_id IS NULL AND rule_revision IS NULL AND execution_epoch IS NULL",Integer.class,match));}}
    @Test @Order(1) void populatedMigrationHasExactProvenanceAndNoInventedHistory(){
        assertEquals(ids.size(),sql.queryForObject("SELECT count(*) FROM rule_versions",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts",Integer.class));
        for(var entry:ids.entrySet()){var view=versions.get(entry.getValue()).orElseThrow();assertEquals("PAUSED",view.lifecycle());assertEquals("PENDING",view.reconciliation());assertNull(view.activeVersionId());assertEquals(0,view.executionEpoch());assertEquals(!entry.getKey().equals("disabled"),view.originalEnabled());
            assertEquals(1,sql.queryForObject("SELECT revision FROM rule_versions WHERE rule_id=?",Integer.class,entry.getValue()));assertEquals("V24_IMPORT",sql.queryForObject("SELECT provenance FROM rule_versions WHERE rule_id=?",String.class,entry.getValue()));assertFalse(sql.queryForObject("SELECT created_at < ? FROM rule_versions WHERE rule_id=?",Boolean.class,Timestamp.from(importStart.minusSeconds(1)),entry.getValue()));assertEquals(30,view.definition().cooldownSeconds());
        }assertTrue(registry.listEnabledOrderedByPriority().isEmpty());preservedHistory();
    }
    @Test @Order(2) void actualMigratedFixturesReconcileWithTruthfulOutcomesAndNoRetroactiveActions(){
        for(var entry:ids.entrySet())if(!Set.of("pending","user_edit","user_pause","parallel").contains(entry.getKey()))lifecycle.reconcile(entry.getValue(),0);
        for(String name:List.of("enabled","cross_device")){var view=versions.get(ids.get(name)).orElseThrow();assertEquals("ACTIVE",view.lifecycle());assertEquals("PASSED",view.reconciliation());assertEquals(1,view.executionEpoch());assertEquals(view.workingVersionId(),view.activeVersionId());}
        assertEquals("PAUSED",versions.get(ids.get("disabled")).orElseThrow().lifecycle());
        for(String name:List.of("invalid_condition","invalid_action","missing_legacy","disabled_binding","ambiguous","conflicting")){var view=versions.get(ids.get(name)).orElseThrow();assertEquals("PAUSED",view.lifecycle());assertNull(view.activeVersionId());assertFalse(view.diagnostics().isEmpty());assertEquals(Set.of("ambiguous","conflicting").contains(name)?"REQUIRES_REVIEW":"FAILED",view.reconciliation());assertTrue(registry.listEnabledOrderedByPriority().stream().noneMatch(r->r.ruleId().equals(view.ruleId())));}
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history",Integer.class));preservedHistory();
        for(String name:List.of("enabled","disabled","cross_device")){var before=versions.get(ids.get(name)).orElseThrow();lifecycle.reconcile(before.ruleId(),before.rowRevision());assertEquals(before,versions.get(before.ruleId()).orElseThrow());assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,before.ruleId()));}
    }
    @Test @Order(3) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void delayedEditPauseConcurrentWorkersAndInfrastructureRollbackAreSafe() throws Exception {
        for(String name:List.of("user_edit","user_pause")){UUID id=ids.get(name);var before=versions.get(id).orElseThrow();var changed=name.equals("user_edit")?lifecycle.draft(new Rule(id,"User replacement",false,1,before.definition().conditions(),before.definition().actions(),Map.of()),before.rowRevision()):lifecycle.transition(id,"pause",before.rowRevision());lifecycle.reconcile(id,before.rowRevision());assertEquals(changed,versions.get(id).orElseThrow());lifecycle.reconcile(id,changed.rowRevision());var after=versions.get(id).orElseThrow();assertEquals("PAUSED",after.lifecycle());assertFalse(after.restorationEligible());assertNull(after.activeVersionId());}
        UUID parallel=ids.get("parallel");var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{var work=(java.util.concurrent.Callable<Void>)()->{start.await();lifecycle.reconcile(parallel,0);return null;};var a=pool.submit(work);var b=pool.submit(work);start.countDown();a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,parallel));assertEquals(1,versions.get(parallel).orElseThrow().executionEpoch());}finally{pool.shutdownNow();}
        UUID pending=ids.get("pending");sql.execute("CREATE FUNCTION closure_import_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.rule_id='"+pending+"'::uuid THEN RAISE EXCEPTION 'interrupted validation persistence'; END IF; RETURN NEW; END $$");sql.execute("CREATE TRIGGER closure_import_failure BEFORE INSERT ON rule_validation_attempts FOR EACH ROW EXECUTE FUNCTION closure_import_failure()");
        assertThrows(RuntimeException.class,()->lifecycle.reconcile(pending,0));assertEquals("PENDING",versions.get(pending).orElseThrow().reconciliation());assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,pending));firstRegistry=registry;
    }
    @Test @Order(4) void freshContextKeepsPendingAndFailedImportsClosedAndCompletedOwnershipStable(){
        assertNotSame(firstRegistry,registry);UUID pending=ids.get("pending");assertEquals("PENDING",versions.get(pending).orElseThrow().reconciliation());assertTrue(registry.listEnabledOrderedByPriority().stream().noneMatch(r->r.ruleId().equals(pending)||r.ruleId().equals(ids.get("invalid_action"))));
        var worker=new RuleImportReconciler(versions,lifecycle);assertThrows(RuntimeException.class,worker::startup);assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,pending));
        sql.execute("DROP TRIGGER closure_import_failure ON rule_validation_attempts");sql.execute("DROP FUNCTION closure_import_failure()");worker.startup();assertEquals("ACTIVE",versions.get(pending).orElseThrow().lifecycle());
        assertEquals(1,versions.get(ids.get("enabled")).orElseThrow().executionEpoch());assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,ids.get("enabled")));preservedHistory();
    }
    @Test @Order(5) void postgresRejectsHistoryMutationAndCrossOwnerAttribution(){
        UUID enabled=ids.get("enabled"),disabled=ids.get("disabled"),wrong=versions.get(disabled).orElseThrow().workingVersionId();
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE rule_validation_attempts SET valid=false WHERE rule_id=?",enabled));assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("DELETE FROM rule_validation_attempts WHERE rule_id=?",enabled));
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("INSERT INTO rule_validation_attempts(attempt_id,rule_id,version_id,purpose,valid,diagnostics,validated_at) VALUES(?,?,?,'TEST',true,'[]',now())",UUID.randomUUID(),enabled,wrong));
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,evaluation_evidence,matched_at,rule_version_id,rule_revision,execution_epoch) VALUES(?,?,'{}',?,'{}',now(),?,1,1)",UUID.randomUUID(),enabled,UUID.randomUUID(),wrong));
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE rules SET active_version_id=? WHERE rule_id=?",wrong,enabled));assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE rule_versions SET definition='{}' WHERE rule_id=?",enabled));assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("DELETE FROM rule_versions WHERE rule_id=?",enabled));preservedHistory();
    }
    @Test @Order(6) void importedGlobalCooldownRetainsOldMatchAuthorityAcrossTriggerDevices(){
        UUID enabled=ids.get("enabled");Instant last=sql.queryForObject("SELECT max(matched_at) FROM rule_matches WHERE rule_id=? AND cooldown_eligible",Timestamp.class,enabled).toInstant();assertTrue(evaluationTime.isBefore(last.plusSeconds(30)));
        for(UUID device:List.of(scope,target))matcher.onEvent(new com.nexus.event.domain.DomainEvent(UUID.randomUUID(),com.nexus.event.domain.EventType.TWIN_UPDATED,device,evaluationTime,"imported-cooldown",Map.of(),"INFO","imported-cooldown"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,enabled));assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m USING(match_id) WHERE m.rule_id=?",Integer.class,enabled));preservedHistory();
    }
}
