package com.nexus.rule.application;

import com.nexus.action.application.*;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.device.capability.*;
import com.nexus.device.binding.BindingManagementService;
import com.nexus.device.binding.api.BindingMutationRequest;
import com.nexus.event.domain.*;
import com.nexus.event.persistence.DomainEventPersister;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RuleCommandAuthorizationIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> broker=new GenericContainer<>("eclipse-mosquitto:2.0").withExposedPorts(1883)
        .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),"/mosquitto/config/mosquitto.conf").waitingFor(Wait.forListeningPort());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
        r.add("spring.flyway.url",db::getJdbcUrl);r.add("spring.flyway.user",db::getUsername);r.add("spring.flyway.password",db::getPassword);
        r.add("mqtt.broker.url",()->"tcp://"+broker.getHost()+":"+broker.getMappedPort(1883));r.add("mqtt.client.id",()->"p31-guard-"+UUID.randomUUID());
    }
    @Autowired JdbcTemplate sql;
    @Autowired RuleLifecycleService lifecycle;
    @org.springframework.boot.test.mock.mockito.SpyBean RuleVersionStore versions;
    @Autowired RuleRegistry registry;
    @Autowired com.nexus.rule.api.RuleController ruleController;
    @org.springframework.boot.test.mock.mockito.SpyBean RuleMatchPersister matches;
    @org.springframework.boot.test.mock.mockito.MockBean java.time.Clock clock;
    @org.junit.jupiter.api.BeforeEach void currentTime(){org.mockito.Mockito.when(clock.instant()).thenAnswer(inv->Instant.now());}
    @Autowired RuleMatchRepository matchHistory;
    @Autowired DomainEventPersister sources;
    @Autowired CommandAcceptanceService acceptance;
    @org.springframework.boot.test.mock.mockito.SpyBean CommandExecutor executor;
    @Autowired RuleMatcher matcher;
    @org.springframework.boot.test.mock.mockito.SpyBean RuleEvaluator evaluator;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.action.application.executor.CreateAlertExecutor alerts;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.action.application.executor.WebhookExecutor webhooks;
    @Autowired RuleCapabilityValidator validator;
    @Autowired com.nexus.twin.domain.DigitalTwinRegistry twins;
    @Autowired com.nexus.twin.domain.DigitalTwinRepository twinRows;
    @Autowired com.nexus.twin.application.TwinStateQualityEvaluator stateQuality;
    @Autowired ActiveRuleReferenceGuard guard;
    @Autowired BindingManagementService bindings;
    @Autowired DeviceCapabilityService capabilities;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.nexus.device.capability.definition.CapabilityDefinitionService definitions;
    @Autowired com.nexus.device.capability.definition.CapabilityDefinitionRepository definitionRows;
    @Autowired com.nexus.device.connectivity.DeviceConnectivityRegistry connectivity;
    record Fixture(UUID device,UUID capability,Rule rule,DomainEvent event,UUID match){}

    // ---- Phase 4 dual-mode support. With the outbox flag OFF (the default and the shipped behaviour) every helper below is exactly
    // the legacy synchronous assertion. With the flag ON (RuleCommandAuthorizationOutboxModeIntegrationTest) a command is first
    // ACCEPTED_FOR_DISPATCH and the dispatcher publishes it afterwards, so the same assertions wait for delivery, and "publishPrepared
    // was called" becomes "publishPrepared was NEVER called (no direct publication) and the outbox delivered it".
    @Autowired com.nexus.action.outbox.CommandOutboxSettings outboxSettings;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.action.outbox.BrokerCommandPublisher brokerPublisher;
    private boolean outboxMode(){return outboxSettings.enabled();}
    private void awaitDelivery(){
        if(!outboxMode())return;
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try{
            while(sql.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')",Integer.class)>0){
                if(System.nanoTime()>end)fail("the dispatcher did not deliver every queued command");
                Thread.sleep(25);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail("interrupted while waiting for delivery");}
    }
    private void assertDirectOrOutbox(){
        if(!outboxMode()){org.mockito.Mockito.verify(executor).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());return;}
        org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        awaitDelivery();
        assertTrue(sql.queryForObject("SELECT count(*) FROM command_outbox WHERE state='PUBLISHED'",Integer.class)>=1,"the outbox delivered the command");
    }
    private void holdPublication(org.mockito.stubbing.Answer<?> answer){
        if(!outboxMode())org.mockito.Mockito.doAnswer(answer).when(executor).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        else org.mockito.Mockito.doAnswer(answer).when(brokerPublisher).publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    private void publishAcceptedAfterPause(ActionExecutionRequest request,CommandExecutor.PreparedCommand prepared){
        if(!outboxMode()){assertEquals(ExecutionStatus.SUCCESS,executor.publishPrepared(request,prepared).status(),"Real disposable Mosquitto publication, not physical actuation");return;}
        awaitDelivery();
        assertEquals("SUCCESS",sql.queryForObject("SELECT status FROM action_history WHERE request_id=?",String.class,request.requestId()),"Real disposable Mosquitto publication by the dispatcher, not physical actuation");
        org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    private UUID device(){
        UUID site=UUID.randomUUID(),building=UUID.randomUUID(),space=UUID.randomUUID(),device=UUID.randomUUID();
        sql.update("INSERT INTO sites(id,name) VALUES(?,'P31')",site);
        sql.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'P31')",building,site);
        sql.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'P31',now(),now())",space,building);
        sql.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES(?,?,'P31','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')",device,space,device.toString());
        connectivity.applyObservedStatus(device,com.nexus.device.connectivity.DeviceConnectivityStatus.ONLINE);
        return device;
    }
    private Fixture fixture(){
        UUID device=device(),cap=UUID.randomUUID(),id=UUID.randomUUID();
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'LED','WRITE','BOOLEAN','{}')",cap,device);
        Rule definition=new Rule(id,"Guarded command",true,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,device.toString())),
            List.of(new RuleAction("SEND_COMMAND",Map.of("deviceId",device.toString(),"capability","LED","command","SET_STATE","value",true))),Map.of());
        lifecycle.legacySave(definition,null);
        Rule pinned=versions.list(true).stream().filter(v->v.ruleId().equals(id)).findFirst().orElseThrow().definition();
        DomainEvent event=new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,device,Instant.now(),"test",Map.of("value",1),"INFO","p31-guard");
        sources.persistRequired(event);UUID match=UUID.randomUUID();
        matches.persist(new RuleMatchedEvent(match,Instant.now(),new RuleEvaluationResult(true,pinned,event,"Matched"),"p31-guard"));
        return new Fixture(device,cap,pinned,event,match);
    }
    @Autowired com.nexus.rule.plan.RuleActionPlanStore planStore;
    /**
     * A command request is only authorizable when the unified arbitration pathway persisted an approved plan that SELECTED it (C13 is
     * retired: there is no source-and-match-only exception). The fixture persists that plan for the legacy event through the real store.
     */
    private ActionExecutionRequest request(Fixture f){
        UUID requestId=UUID.randomUUID();var action=f.rule().actions().getFirst();
        var candidate=new com.nexus.rule.plan.ActionCandidate(UUID.randomUUID(),f.match(),f.rule(),0,action,requestId,
            com.nexus.rule.plan.ActionArbiter.targetKey(action),com.nexus.rule.plan.ActionArbiter.equivalenceKey(action),f.rule().priority(),"p31-guard");
        if(planStore.persist(com.nexus.rule.plan.PlanSource.event(f.event().eventId()),List.of(candidate)).isEmpty()){
            // The fixture already has its plan: add one more SELECTED candidate to it.
            UUID plan=sql.queryForObject("SELECT plan_id FROM rule_action_plans WHERE event_id=?",UUID.class,f.event().eventId());
            int ordinal=sql.queryForObject("SELECT count(*) FROM rule_action_candidates WHERE plan_id=?",Integer.class,plan);
            sql.update("INSERT INTO rule_action_candidates(candidate_id,plan_id,match_id,rule_id,rule_version_id,rule_revision,action_ordinal,action_type,target_key,priority,request_id,disposition,reason,parameters) VALUES(?,?,?,?,?,?,?,'SEND_COMMAND',?,?,?,'SELECTED','FIXTURE_SECOND_REQUEST',?::jsonb)",
                UUID.randomUUID(),plan,f.match(),f.rule().ruleId(),f.rule().executionIdentity().ruleVersionId(),f.rule().executionIdentity().ruleRevision(),ordinal+1,candidate.targetKey(),candidate.priority(),requestId,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(action.parameters()).toString());
        }
        return new ActionExecutionRequest(requestId,f.match(),ActionType.SEND_COMMAND,action.parameters());
    }
    private void empty(Fixture f,ActionExecutionRequest request){
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history WHERE request_id=?",Integer.class,request.requestId()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?",Integer.class,f.device()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM current_twin_verification_state WHERE device_id=?",Integer.class,f.device()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM twin_state_transitions WHERE command_id=?",Integer.class,request.requestId()));
    }
    private int count(String table,UUID device){return sql.queryForObject("SELECT count(*) FROM "+table+" WHERE device_id=?",Integer.class,device);}
    private void noEffects(UUID device){
        assertEquals(0,count("action_history",device));assertEquals(0,count("current_desired_twin_state",device));
        assertEquals(0,count("current_twin_verification_state",device));assertEquals(0,count("twin_state_transitions",device));
        org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(alerts,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(webhooks,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    private com.nexus.device.capability.definition.CapabilityDefinition readDefinition(){
        return definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"audit_temperature_"+UUID.randomUUID().toString().replace("-",""),"Audit temperature",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.NUMBER,"CELSIUS",List.of("CELSIUS","FAHRENHEIT"),null,null,List.of(),List.of(),null,Map.of(),Map.of()));
    }
    private Rule numericRule(Fixture f,String key,String unit){
        return new Rule(f.rule().ruleId(),"Unit/reference safety",false,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,f.device().toString()),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",null,key,unit)),f.rule().actions(),Map.of());
    }
    private void reported(UUID device,UUID binding,com.nexus.device.capability.definition.CapabilityDefinition d){
        twins.put(new com.nexus.twin.domain.DigitalTwin(device,Instant.now(),d.getKey(),new java.math.BigDecimal("40"),"CELSIUS",Instant.now(),Instant.now(),Map.of(),d.getId(),binding,d.getKey(),d.getValueType(),com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING));
    }
    record LegacyFixture(Fixture command,com.nexus.device.capability.definition.CapabilityDefinition definition,UUID binding){}
    private LegacyFixture legacyFixture(boolean bound){
        var f=fixture();var d=definitionRows.findByKey("temperature").orElseThrow();
        if(!d.getAllowedUnits().contains("FAHRENHEIT")){
            definitions.update(d.getId(),new com.nexus.device.capability.definition.api.UpdateCapabilityDefinitionRequest(d.getDisplayName(),d.getDescription(),d.getUnit(),List.of("CELSIUS","FAHRENHEIT"),d.getMinimum(),d.getMaximum(),d.getEnumValues(),d.getSupportedCommands(),d.getSemanticCategory(),d.getObjectConstraints(),d.getMetadata()));
            d=definitionRows.findById(d.getId()).orElseThrow();
        }
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'TEMPERATURE','READ','CELSIUS','{}')",UUID.randomUUID(),f.device());
        UUID binding=bound?bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),Map.of("telemetryKey","TemperatureAlias"))).id():null;
        return new LegacyFixture(f,d,binding);
    }
    private Rule legacyDefinition(LegacyFixture f,String unit){
        return new Rule(f.command().rule().ruleId(),"Legacy canonical temperature",true,1,List.of(
            new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,f.command().device().toString()),
            new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",CapabilityType.TEMPERATURE,null,unit)),f.command().rule().actions(),Map.of("purpose","forensic-legacy"));
    }
    private Rule activateLegacy(LegacyFixture f,String unit){
        var definition=legacyDefinition(f,unit);lifecycle.legacySave(definition,null);
        return registry.listEnabledOrderedByPriority().stream().filter(r->r.ruleId().equals(definition.ruleId())).findFirst().orElseThrow();
    }
    private com.nexus.twin.domain.DigitalTwin legacyReported(LegacyFixture f,String value,String unit,String wire,Instant received){
        var d=f.definition();var state=new com.nexus.twin.domain.DigitalTwin(f.command().device(),Instant.now(),d.getKey(),wire,new java.math.BigDecimal(value),unit,Instant.now(),received,Map.of(),d.getId(),f.binding(),d.getKey(),d.getValueType(),
            f.binding()==null?com.nexus.device.binding.runtime.CapabilityResolutionSource.LEGACY_ADAPTER:com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING);
        twins.put(state);
        var durable=twinRows.findById(new com.nexus.twin.domain.DigitalTwinId(state.deviceId(),state.stateKey())).orElseThrow();
        assertEquals(f.binding(),durable.bindingId());assertEquals(unit,durable.latestUnit());assertEquals(0,new java.math.BigDecimal(value).compareTo((java.math.BigDecimal)durable.typedValue()));
        return state;
    }
    private DomainEvent legacyTrigger(LegacyFixture f){return new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.command().device(),Instant.now(),"legacy-forensic",Map.of("sensorType","TEMPERATURE"),"INFO","legacy-forensic-"+UUID.randomUUID());}
    private void noLegacyEffects(LegacyFixture f,DomainEvent source){
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM domain_events WHERE event_id=? AND device_id=? AND correlation_id=?",Integer.class,source.eventId(),source.deviceId(),source.correlationId()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id=?",Integer.class,f.command().rule().ruleId(),source.eventId()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.command().rule().ruleId()),"Existing seed history survives without a new false match");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m USING(match_id) WHERE m.rule_id=?",Integer.class,f.command().rule().ruleId()));
        noEffects(f.command().device());
    }
    record NumericPipelineFixture(Fixture command,com.nexus.device.capability.definition.CapabilityDefinition definition,UUID binding){}
    private NumericPipelineFixture numericPipelineFixture(String defaultUnit,List<String> allowedUnits,String bindingUnit,boolean legacyAlias){
        var command=fixture();
        var definition=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"r1_numeric_"+UUID.randomUUID().toString().replace("-",""),"R1 numeric provenance",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.NUMBER,defaultUnit,allowedUnits,null,null,List.of(),List.of(),null,Map.of(),Map.of()));
        if(legacyAlias)sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'TEMPERATURE','READ',?,'{}')",UUID.randomUUID(),command.device(),bindingUnit);
        var binding=bindings.create(command.device(),new BindingMutationRequest(definition.getId(),true,bindingUnit,Map.of(),Map.of("telemetryKey",legacyAlias?"temperature":"R1NumericAlias")));
        return new NumericPipelineFixture(command,definition,binding.id());
    }
    private Rule numericPipelineRule(NumericPipelineFixture f,boolean legacy,String conditionUnit,boolean includeNonCommandActions){
        var actions=new ArrayList<>(f.command().rule().actions());
        if(includeNonCommandActions){actions.add(new RuleAction("CREATE_ALERT",Map.of()));actions.add(new RuleAction("WEBHOOK",Map.of("url","https://example.invalid")));}
        return new Rule(f.command().rule().ruleId(),"R1 actual numeric pipeline",true,1,List.of(
            new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,f.command().device().toString()),
            new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",legacy?CapabilityType.TEMPERATURE:null,legacy?null:f.definition().getKey(),conditionUnit)),actions,Map.of("purpose","r1-unit-provenance"));
    }
    private Rule activateNumericPipeline(Rule definition){
        assertTrue(validator.inspect(definition).isEmpty(),"The installed condition/action contract is legitimate before runtime observation eligibility");
        lifecycle.legacySave(definition,null);
        return registry.listEnabledOrderedByPriority().stream().filter(r->r.ruleId().equals(definition.ruleId())).findFirst().orElseThrow();
    }
    private com.nexus.twin.domain.DigitalTwin numericPipelineReported(NumericPipelineFixture f,String value,String unit){
        Instant received=Instant.now();var d=f.definition();
        var state=new com.nexus.twin.domain.DigitalTwin(f.command().device(),received,d.getKey(),"R1NumericAlias",new java.math.BigDecimal(value),unit,received,received,Map.of(),d.getId(),f.binding(),d.getKey(),d.getValueType(),com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING);
        twins.put(state);
        var durable=twinRows.findById(new com.nexus.twin.domain.DigitalTwinId(state.deviceId(),state.stateKey())).orElseThrow();
        assertEquals(f.binding(),durable.bindingId());assertEquals(d.getId(),durable.capabilityDefinitionId());assertEquals(unit,durable.latestUnit());assertEquals(0,new java.math.BigDecimal(value).compareTo((java.math.BigDecimal)durable.typedValue()));
        assertEquals(com.nexus.twin.domain.TwinStateQuality.GOOD,stateQuality.evaluate(durable).state(),"Fresh ONLINE GOOD quality must not manufacture missing or mismatched unit provenance");
        return durable;
    }
    private DomainEvent numericPipelineTrigger(NumericPipelineFixture f,com.nexus.twin.domain.DigitalTwin observation){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("value",observation.typedValue());payload.put("unit",observation.latestUnit());payload.put("bindingId",f.binding().toString());payload.put("capabilityKey",f.definition().getKey());
        return new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.command().device(),Instant.now(),"r1-pipeline",payload,"INFO","r1-pipeline-"+UUID.randomUUID());
    }
    private String numericObservationSnapshot(NumericPipelineFixture f){return sql.queryForObject("SELECT row_to_json(t)::text FROM digital_twins t WHERE device_id=? AND state_key=?",String.class,f.command().device(),"binding:"+f.binding());}
    private void assertNumericSourcePersisted(NumericPipelineFixture f,DomainEvent source,com.nexus.twin.domain.DigitalTwin observation){
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM domain_events WHERE event_id=? AND device_id=? AND correlation_id=? AND event_type='TWIN_UPDATED' AND source='r1-pipeline'",Integer.class,source.eventId(),f.command().device(),source.correlationId()));
        assertEquals(observation.typedValue().toString(),sql.queryForObject("SELECT payload->>'value' FROM domain_events WHERE event_id=?",String.class,source.eventId()));
        assertEquals(observation.latestUnit(),sql.queryForObject("SELECT payload->>'unit' FROM domain_events WHERE event_id=?",String.class,source.eventId()));
        assertEquals(f.binding().toString(),sql.queryForObject("SELECT payload->>'bindingId' FROM domain_events WHERE event_id=?",String.class,source.eventId()));
        assertEquals(f.definition().getKey(),sql.queryForObject("SELECT payload->>'capabilityKey' FROM domain_events WHERE event_id=?",String.class,source.eventId()));
    }
    private void noNumericPipelineEffects(NumericPipelineFixture f,DomainEvent source,com.nexus.twin.domain.DigitalTwin observation,String before){
        assertNumericSourcePersisted(f,source,observation);
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id=?",Integer.class,f.command().rule().ruleId(),source.eventId()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.command().rule().ruleId()),"Only the deliberately seeded historical match remains");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m USING(match_id) WHERE m.rule_id=? OR m.event_id=?",Integer.class,f.command().rule().ruleId(),source.eventId()));
        noEffects(f.command().device());
        assertEquals(before,numericObservationSnapshot(f),"Evaluation neither invents a historical unit nor rewrites the reported observation");
    }
    @Test void genericDimensionalNullUnitsCannotPersistMatchOrAcceptAnySideEffect(){
        var f=numericPipelineFixture(null,List.of("CELSIUS","FAHRENHEIT"),null,false);
        assertNull(f.definition().getUnit());assertNull(validator.resolveInstalled(f.command().device(),f.definition().getKey(),CapabilityDirection.READ).effectiveUnit());
        var observation=numericPipelineReported(f,"40",null);var before=numericObservationSnapshot(f);
        var active=activateNumericPipeline(numericPipelineRule(f,false,null,true));var source=numericPipelineTrigger(f,observation);
        var evaluated=evaluator.evaluate(active,source);
        assertEquals(f.binding(),evaluated.evidence().get(1).bindingId());assertEquals("GOOD",evaluated.evidence().get(1).stateQuality());
        assertFalse(evaluated.matched(),"A dimensional definition with concrete allowed units cannot compare two missing unit identities");
        matcher.onEvent(source);noNumericPipelineEffects(f,source,observation,before);
    }
    @Test void genericAndLegacyShareMissingDimensionalProvenanceRejection(){
        var f=numericPipelineFixture(null,List.of("CELSIUS","FAHRENHEIT"),null,true);
        var genericReference=validator.resolveInstalled(f.command().device(),f.definition().getKey(),CapabilityDirection.READ);
        var legacyReference=validator.resolveInstalled(f.command().device(),"temperature",CapabilityDirection.READ);
        assertEquals(genericReference.binding().getId(),legacyReference.binding().getId());assertEquals(genericReference.definition().getId(),legacyReference.definition().getId());assertNull(genericReference.effectiveUnit());assertNull(legacyReference.effectiveUnit());
        var observation=numericPipelineReported(f,"40",null);var before=numericObservationSnapshot(f);
        for(boolean legacy:new boolean[]{true,false}){
            var active=activateNumericPipeline(numericPipelineRule(f,legacy,null,true));var source=numericPipelineTrigger(f,observation);var evaluated=evaluator.evaluate(active,source);
            assertEquals(f.binding(),evaluated.evidence().get(1).bindingId());assertEquals(f.definition().getId(),evaluated.evidence().get(1).definitionId());assertEquals("GOOD",evaluated.evidence().get(1).stateQuality());
            assertFalse(evaluated.matched(),"Equivalent legitimate legacy/generic syntax must both fail closed on the same dimensional NULL-unit observation");
            matcher.onEvent(source);noNumericPipelineEffects(f,source,observation,before);
        }
    }
    @Test void genericMissingOrMismatchedReportedUnitsCannotReachAnySideEffect(){
        var f=numericPipelineFixture("CELSIUS",List.of("CELSIUS","FAHRENHEIT"),"CELSIUS",false);
        for(String conditionUnit:Arrays.asList("CELSIUS",null)){
            var active=activateNumericPipeline(numericPipelineRule(f,false,conditionUnit,true));
            for(String reportedUnit:Arrays.asList(null,"FAHRENHEIT")){
                var observation=numericPipelineReported(f,"40",reportedUnit);var before=numericObservationSnapshot(f);var source=numericPipelineTrigger(f,observation);
                assertFalse(evaluator.evaluate(active,source).matched(),"GOOD is insufficient when reported units are missing or incompatible with installed CELSIUS");
                matcher.onEvent(source);noNumericPipelineEffects(f,source,observation,before);
            }
        }
    }
    @Test void genuineUnitlessAndCompatibleNumericConditionsPersistAndPublishRealMqttCommands() throws Exception {
        for(String unit:Arrays.asList(null,"CELSIUS")){
            var f=numericPipelineFixture(unit,unit==null?List.of():List.of("CELSIUS","FAHRENHEIT"),unit,false);
            var active=activateNumericPipeline(numericPipelineRule(f,false,unit,false));
            var below=numericPipelineReported(f,"30",unit);var belowSource=numericPipelineTrigger(f,below);var before=numericObservationSnapshot(f);
            assertFalse(evaluator.evaluate(active,belowSource).matched());matcher.onEvent(belowSource);noNumericPipelineEffects(f,belowSource,below,before);
            var received=new java.util.concurrent.ArrayBlockingQueue<org.eclipse.paho.client.mqttv3.MqttMessage>(2);
            var observer=new org.eclipse.paho.client.mqttv3.MqttClient("tcp://"+broker.getHost()+":"+broker.getMappedPort(1883),"r1-observer-"+UUID.randomUUID(),new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
            try{
                observer.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback(){
                    @Override public void connectionLost(Throwable cause){}
                    @Override public void messageArrived(String topic,org.eclipse.paho.client.mqttv3.MqttMessage message){received.offer(message);}
                    @Override public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token){}
                });
                var options=new org.eclipse.paho.client.mqttv3.MqttConnectOptions();options.setCleanSession(true);options.setConnectionTimeout(10);observer.connect(options);
                observer.subscribe("nexus/devices/"+f.command().device()+"/commands",1);
                var observation=numericPipelineReported(f,"40",unit);var source=numericPipelineTrigger(f,observation);assertTrue(evaluator.evaluate(active,source).matched());
                matcher.onEvent(source);assertNumericSourcePersisted(f,source,observation);
                UUID match=sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=? AND event_id=?",UUID.class,active.ruleId(),source.eventId());
                @SuppressWarnings("unchecked") var evidence=(List<Map<String,Object>>)matchHistory.findById(match).orElseThrow().getEvaluationEvidence().get("evaluatedConditions");
                assertEquals(f.binding().toString(),evidence.get(1).get("bindingId").toString());assertEquals(f.definition().getId().toString(),evidence.get(1).get("definitionId").toString());assertEquals(unit,evidence.get(1).get("unit"));
                awaitDelivery();assertEquals(1,sql.queryForObject("SELECT count(*) FROM action_history WHERE match_id=? AND device_id=? AND status='SUCCESS'",Integer.class,match,f.command().device()));
                UUID command=sql.queryForObject("SELECT request_id FROM action_history WHERE match_id=? AND device_id=?",UUID.class,match,f.command().device());
                assertEquals(command,sql.queryForObject("SELECT command_id FROM current_desired_twin_state WHERE device_id=?",UUID.class,f.command().device()));
                assertEquals(command,sql.queryForObject("SELECT desired_command_id FROM current_twin_verification_state WHERE device_id=?",UUID.class,f.command().device()));
                assertTrue(sql.queryForObject("SELECT count(*) FROM twin_state_transitions WHERE device_id=? AND command_id=?",Integer.class,f.command().device(),command)>0);
                var message=received.poll(15,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(message,"A real Mosquitto subscriber must receive the accepted command");assertEquals(1,message.getQos());assertFalse(message.isRetained());
                var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.getPayload());assertEquals(command.toString(),payload.get("commandId").asText());assertEquals("LED",payload.get("capability").asText());assertEquals("SET_STATE",payload.get("command").asText());assertTrue(payload.get("value").asBoolean());
            }finally{if(observer.isConnected())observer.disconnect();observer.close();}
            // Subsequent independent fixture negatives must not see the preceding accepted command in global spy counters.
            org.mockito.Mockito.clearInvocations(executor,alerts,webhooks);
        }
    }
    @Test void legacyExplicitAndOmittedUnitMismatchCannotPersistMatchOrAcceptAction(){
        for(String unit:Arrays.asList("CELSIUS",null)){
            var f=legacyFixture(true);legacyReported(f,"40","CELSIUS","TEMPERATURE",Instant.now());
            var active=activateLegacy(f,unit);assertTrue(evaluator.evaluate(active,f.command().event()).matched());
            var owner=versions.get(active.ruleId()).orElseThrow();var wrong=legacyReported(f,"86","FAHRENHEIT","TEMPERATURE",Instant.now());
            assertEquals(com.nexus.twin.domain.TwinStateQuality.GOOD,stateQuality.evaluate(wrong).state());
            assertFalse(evaluator.evaluate(active,f.command().event()).matched());
            var source=legacyTrigger(f);matcher.onEvent(source);noLegacyEffects(f,source);
            assertEquals(owner,versions.get(active.ruleId()).orElseThrow());
        }
    }
    @Test void legacyConditionUnitMismatchHasDurableIndexedActivationFailure(){
        var f=legacyFixture(true);var before=versions.get(f.command().rule().ruleId()).orElseThrow();
        var draft=lifecycle.draft(legacyDefinition(f,"FAHRENHEIT"),before.rowRevision());var failed=lifecycle.transition(before.ruleId(),"activate",draft.rowRevision());
        assertEquals("UNIT_MISMATCH",failed.diagnostics().getFirst().get("code"));assertEquals("conditions[1]",failed.diagnostics().getFirst().get("path"));
        assertEquals(before.activeVersionId(),failed.activeVersionId());assertEquals(before.executionEpoch(),failed.executionEpoch());assertEquals(draft.rowRevision(),failed.rowRevision());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=? AND version_id=? AND valid=false",Integer.class,before.ruleId(),draft.workingVersionId()));noEffects(f.command().device());
    }
    @Test void legacyCanonicalBindingIgnoresRetainedAliasAndMatchesEquivalentGeneric(){
        var f=legacyFixture(true);legacyReported(f,"20","CELSIUS","TemperatureAlias",Instant.now());
        var other=readDefinition();var otherBinding=bindings.create(f.command().device(),new BindingMutationRequest(other.getId(),true,"CELSIUS",Map.of(),Map.of("telemetryKey","OtherAlias"))).id();
        var retained=new com.nexus.twin.domain.DigitalTwin(f.command().device(),Instant.now(),other.getKey(),"TEMPERATURE",new java.math.BigDecimal("99"),"CELSIUS",Instant.now(),Instant.now(),Map.of(),other.getId(),otherBinding,other.getKey(),other.getValueType(),com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING);twins.put(retained);
        String retainedBefore=sql.queryForObject("SELECT row_to_json(t)::text FROM digital_twins t WHERE device_id=? AND state_key=?",String.class,retained.deviceId(),retained.stateKey());
        var active=activateLegacy(f,null);var generic=numericRule(f.command(),"tEmPeRaTuReAlIaS",null);assertTrue(validator.inspect(generic).isEmpty());
        var result=evaluator.evaluate(active,f.command().event());assertFalse(result.matched());assertEquals(20.0,result.evidence().get(1).actualValue());assertEquals(f.binding(),result.evidence().get(1).bindingId());assertFalse(evaluator.evaluate(generic,f.command().event()).matched());
        var below=legacyTrigger(f);matcher.onEvent(below);noLegacyEffects(f,below);
        for(String wire:List.of("TemperatureAlias","temperaturealias","tEmPeRaTuReAlIaS")){
            legacyReported(f,"40","CELSIUS",wire,Instant.now());result=evaluator.evaluate(active,f.command().event());assertTrue(result.matched());assertTrue(evaluator.evaluate(generic,f.command().event()).matched());
            assertEquals(f.binding(),result.evidence().get(1).bindingId());assertEquals(f.definition().getId(),result.evidence().get(1).definitionId());assertEquals("temperature",result.evidence().get(1).capabilityKey());
        }
        var source=legacyTrigger(f);matcher.onEvent(source);
        var match=sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=? AND event_id=?",UUID.class,active.ruleId(),source.eventId());
        @SuppressWarnings("unchecked") var evidence=(List<Map<String,Object>>)matchHistory.findById(match).orElseThrow().getEvaluationEvidence().get("evaluatedConditions");
        assertEquals(f.binding().toString(),evidence.get(1).get("bindingId").toString());assertEquals(f.definition().getId().toString(),evidence.get(1).get("definitionId").toString());assertEquals("temperature",evidence.get(1).get("capabilityKey"));assertEquals("CELSIUS",evidence.get(1).get("unit"));assertEquals(40.0,((Number)evidence.get(1).get("actualValue")).doubleValue());
        awaitDelivery();assertEquals(1,sql.queryForObject("SELECT count(*) FROM action_history WHERE match_id=? AND device_id=? AND status='SUCCESS'",Integer.class,match,f.command().device()));assertEquals(1,count("current_desired_twin_state",f.command().device()));assertEquals(1,count("current_twin_verification_state",f.command().device()));
        assertDirectOrOutbox();
        assertEquals(retainedBefore,sql.queryForObject("SELECT row_to_json(t)::text FROM digital_twins t WHERE device_id=? AND state_key=?",String.class,retained.deviceId(),retained.stateKey()),"Historical alias row is preserved exactly");
    }
    @Test void legacyCanonicalEvaluationIgnoresOtherDeviceAndInactiveWorkingDraft(){
        var f=legacyFixture(true);legacyReported(f,"20","CELSIUS","TemperatureAlias",Instant.now());var active=activateLegacy(f,null);
        UUID other=device();var otherBinding=bindings.create(other,new BindingMutationRequest(f.definition().getId(),true,"CELSIUS",Map.of(),Map.of("telemetryKey","TemperatureAlias"))).id();
        twins.put(new com.nexus.twin.domain.DigitalTwin(other,Instant.now(),"temperature","TemperatureAlias",new java.math.BigDecimal("99"),"CELSIUS",Instant.now(),Instant.now(),Map.of(),f.definition().getId(),otherBinding,"temperature",f.definition().getValueType(),com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING));
        var before=versions.get(active.ruleId()).orElseThrow();var draft=lifecycle.draft(new Rule(active.ruleId(),"Inactive low threshold",false,1,List.of(active.conditions().getFirst(),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"5",CapabilityType.TEMPERATURE)),active.actions(),Map.of("owner","draft")),before.rowRevision());
        assertEquals(before.activeVersionId(),draft.activeVersionId());assertEquals(before.executionEpoch(),draft.executionEpoch());assertNotEquals(draft.activeVersionId(),draft.workingVersionId());
        var selected=registry.listEnabledOrderedByPriority().stream().filter(r->r.ruleId().equals(active.ruleId())).findFirst().orElseThrow();assertEquals("35",selected.conditions().get(1).expectedValue());
        var result=evaluator.evaluate(selected,f.command().event());assertFalse(result.matched());assertEquals(f.binding(),result.evidence().get(1).bindingId());assertEquals(20.0,result.evidence().get(1).actualValue());
        var source=legacyTrigger(f);matcher.onEvent(source);noLegacyEffects(f,source);
        matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,other,Instant.now(),"other",Map.of(),"INFO","other-legacy"));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,active.ruleId()));noEffects(other);
        assertEquals(2,twinRows.findAll().stream().filter(t->t.deviceId().equals(other)||t.deviceId().equals(f.command().device())).count());
    }
    @Test void legacyAmbiguousAndDisabledInstallationSuppressesActivationAndRuntime(){
        var f=legacyFixture(true);legacyReported(f,"40","CELSIUS","TEMPERATURE",Instant.now());var active=activateLegacy(f,null);var before=versions.get(active.ruleId()).orElseThrow();
        var other=readDefinition();UUID ambiguous=UUID.randomUUID();sql.update("INSERT INTO device_capability_bindings(id,device_id,capability_definition_id,enabled,unit,external_identifiers,created_at,updated_at) VALUES(?,?,?,true,'CELSIUS','{\"telemetryKey\":\"TEMPERATURE\"}',now(),now())",ambiguous,f.command().device(),other.getId());
        for(boolean disabled:new boolean[]{false,true}){
            if(disabled){sql.update("UPDATE device_capability_bindings SET external_identifiers='{\"telemetryKey\":\"OtherAlias\"}' WHERE id=?",ambiguous);sql.update("UPDATE device_capability_bindings SET enabled=false WHERE id=?",f.binding());}
            var failed=lifecycle.transition(active.ruleId(),"activate",before.rowRevision());assertFalse(failed.diagnostics().isEmpty());assertEquals(before.activeVersionId(),failed.activeVersionId());assertEquals(before.executionEpoch(),failed.executionEpoch());
            assertFalse(evaluator.evaluate(active,f.command().event()).matched());var source=legacyTrigger(f);matcher.onEvent(source);noLegacyEffects(f,source);
        }
    }
    @Test void legacyMissingUnknownStaleAndUnexpectedContractUnitsCannotCreateEffects(){
        var f=legacyFixture(true);var active=activateLegacy(f,null);
        for(String unit:Arrays.asList(null,"UNKNOWN","CELSIUS")){
            var state=legacyReported(f,"40",unit,"TEMPERATURE",unit!=null&&unit.equals("CELSIUS")?Instant.now().minusSeconds(3600):Instant.now());
            if(unit==null)assertEquals(com.nexus.twin.domain.TwinStateQuality.GOOD,stateQuality.evaluate(state).state(),"Quality alone cannot invent missing unit provenance");
            assertFalse(evaluator.evaluate(active,f.command().event()).matched());var source=legacyTrigger(f);matcher.onEvent(source);noLegacyEffects(f,source);
        }
        legacyReported(f,"40","CELSIUS","TEMPERATURE",Instant.now());sql.update("UPDATE device_capability_bindings SET unit='FAHRENHEIT' WHERE id=?",f.binding());
        assertFalse(evaluator.evaluate(active,f.command().event()).matched());var source=legacyTrigger(f);matcher.onEvent(source);noLegacyEffects(f,source);
    }
    @Test void legacyUnboundInstalledCapabilityUsesCanonicalUnitSafeState(){
        var f=legacyFixture(false);var active=activateLegacy(f,"CELSIUS");
        for(String unit:Arrays.asList(null,"FAHRENHEIT")){
            legacyReported(f,"86",unit,"TEMPERATURE_SENSOR",Instant.now());assertFalse(evaluator.evaluate(active,f.command().event()).matched());var source=legacyTrigger(f);matcher.onEvent(source);noLegacyEffects(f,source);
        }
        legacyReported(f,"30","CELSIUS","TEMPERATURE_SENSOR",Instant.now());assertFalse(evaluator.evaluate(active,f.command().event()).matched());var below=legacyTrigger(f);matcher.onEvent(below);noLegacyEffects(f,below);
        legacyReported(f,"40","CELSIUS","TEMPERATURE_SENSOR",Instant.now());assertTrue(evaluator.evaluate(active,f.command().event()).matched());var source=legacyTrigger(f);matcher.onEvent(source);
        UUID match=sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=? AND event_id=?",UUID.class,active.ruleId(),source.eventId());awaitDelivery();assertEquals(1,sql.queryForObject("SELECT count(*) FROM action_history WHERE match_id=? AND status='SUCCESS'",Integer.class,match));
        @SuppressWarnings("unchecked") var evidence=(List<Map<String,Object>>)matchHistory.findById(match).orElseThrow().getEvaluationEvidence().get("evaluatedConditions");assertNull(evidence.get(1).get("bindingId"));assertEquals("temperature",evidence.get(1).get("capabilityKey"));assertEquals("CELSIUS",evidence.get(1).get("unit"));assertDirectOrOutbox();
    }
    @Test void effectiveUnitMismatchCannotActivateOrReachAnySideEffect(){
        var f=fixture();var d=readDefinition();var binding=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),Map.of("telemetryKey","TemperatureAlias")));
        reported(f.device(),binding.id(),d);var original=versions.get(f.rule().ruleId()).orElseThrow();
        var invalid=numericRule(f,d.getKey(),"FAHRENHEIT");var draft=lifecycle.draft(invalid,original.rowRevision());
        var refused=lifecycle.transition(f.rule().ruleId(),"activate",draft.rowRevision());
        assertEquals(original.activeVersionId(),refused.activeVersionId());assertEquals(original.executionEpoch(),refused.executionEpoch());
        assertEquals("UNIT_MISMATCH",refused.diagnostics().getFirst().get("code"));assertEquals("conditions[1]",refused.diagnostics().getFirst().get("path"));
        assertTrue(refused.diagnostics().getFirst().get("message").toString().contains("CELSIUS"));
        assertFalse(evaluator.evaluate(invalid,f.event()).matched(),"Even direct evaluation must never compare incompatible raw units");
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=? AND version_id=? AND valid=false",Integer.class,f.rule().ruleId(),draft.workingVersionId()));
        noEffects(f.device());
    }
    @Test void exactAndOmittedUnitsUseInstalledBindingAndCanonicalAlias(){
        var f=fixture();var d=readDefinition();var binding=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),Map.of("telemetryKey","TemperatureAlias")));reported(f.device(),binding.id(),d);
        for(String unit:new String[]{"CELSIUS",null}){
            var rule=numericRule(f,"tEmPeRaTuReAlIaS",unit);assertTrue(validator.inspect(rule).isEmpty());
            var saved=lifecycle.legacySave(new Rule(rule.ruleId(),rule.name(),true,rule.priority(),rule.conditions(),rule.actions(),rule.metadata()),null);
            var result=evaluator.evaluate(versions.list(true).stream().filter(v->v.ruleId().equals(rule.ruleId())).findFirst().orElseThrow().definition(),f.event());
            assertTrue(result.matched());assertEquals(binding.id().toString(),result.evidence().get(1).bindingId().toString());assertEquals("CELSIUS",result.evidence().get(1).unit());assertEquals("ACTIVE",saved.lifecycle());
        }
    }
    @Test void supportedUnitMutationIsProtectedAndUnexpectedUnitChangeSuppressesRuntime(){
        var f=fixture();var d=readDefinition();var binding=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),null));reported(f.device(),binding.id(),d);
        var rule=numericRule(f,d.getKey(),"CELSIUS");var active=lifecycle.legacySave(new Rule(rule.ruleId(),rule.name(),true,rule.priority(),rule.conditions(),rule.actions(),rule.metadata()),null);
        assertThrows(IllegalStateException.class,()->bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,null,"FAHRENHEIT",null,null)));
        assertEquals(active.activeVersionId(),versions.get(rule.ruleId()).orElseThrow().activeVersionId());
        sql.update("UPDATE device_capability_bindings SET unit='FAHRENHEIT' WHERE id=?",binding.id());
        matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.device(),Instant.now(),"test",Map.of(),"INFO","unit-runtime"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,rule.ruleId()));noEffects(f.device());
    }
    @Test void canonicalReadReferenceProtectsCaseAliasAndNotAnotherDeviceOrDraft(){
        var f=fixture();var d=readDefinition();var binding=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),Map.of("telemetryKey","AliasTemp")));reported(f.device(),binding.id(),d);
        var rule=numericRule(f,"aLiAsTeMp",null);var active=lifecycle.legacySave(new Rule(rule.ruleId(),rule.name(),true,rule.priority(),rule.conditions(),rule.actions(),rule.metadata()),null);
        assertTrue(guard.references(f.device(),d.getKey()));assertThrows(IllegalStateException.class,()->bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,false,null,null,null)));
        assertEquals(active.activeVersionId(),versions.get(rule.ruleId()).orElseThrow().activeVersionId());assertTrue(evaluator.evaluate(versions.list(true).stream().filter(v->v.ruleId().equals(rule.ruleId())).findFirst().orElseThrow().definition(),f.event()).matched());
        UUID other=device();var independent=bindings.create(other,new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),null));assertFalse(guard.references(other,d.getKey()));bindings.update(other,independent.id(),new BindingMutationRequest(null,false,null,null,null));
        var unused=readDefinition();var unusedBinding=bindings.create(f.device(),new BindingMutationRequest(unused.getId(),true,"CELSIUS",Map.of(),null));
        lifecycle.draft(numericRule(f,unused.getKey().toUpperCase(Locale.ROOT),null),active.rowRevision());assertFalse(guard.references(f.device(),unused.getKey()));bindings.update(f.device(),unusedBinding.id(),new BindingMutationRequest(null,false,null,null,null));
        lifecycle.transition(rule.ruleId(),"pause",null);assertFalse(guard.references(f.device(),d.getKey()));bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,false,null,null,null));
        lifecycle.transition(rule.ruleId(),"archive",null);assertFalse(guard.references(f.device(),d.getKey()));
    }
    @Test void canonicalCrossDeviceWriteReferenceProtectsCaseAndAlias(){
        var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);
        var binding=bindings.create(f.device(),new BindingMutationRequest(sql.queryForObject("SELECT id FROM capability_definitions WHERE capability_key='led'",UUID.class),true,null,Map.of(),Map.of("commandCapability","LedAlias")));
        UUID scope=device();
        for(String identifier:List.of("LeD","lEdAlIaS")){
            var definition=new Rule(f.rule().ruleId(),"Cross-device canonical",true,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,scope.toString().toUpperCase(Locale.ROOT))),List.of(new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString().toUpperCase(Locale.ROOT),"capabilityKey",identifier,"command","SET_STATE","value",true))),Map.of());
            var active=lifecycle.legacySave(definition,null);assertTrue(guard.references(f.device(),"led"));
            assertThrows(IllegalStateException.class,()->bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,false,null,null,null)));
            assertEquals(active.activeVersionId(),versions.get(definition.ruleId()).orElseThrow().activeVersionId());
        }
        UUID source=UUID.randomUUID();matcher.onEvent(new DomainEvent(source,EventType.TWIN_UPDATED,scope,Instant.now(),"cross-device",Map.of(),"INFO","cross-device"));
        assertTrue(ruleController.listRules(scope.toString()).getBody().stream().anyMatch(r->r.ruleId().equals(f.rule().ruleId())),"Device filter must use the same canonical UUID as validation and execution");
        assertEquals(1,count("action_history",f.device()));awaitDelivery();assertEquals("SUCCESS",sql.queryForObject("SELECT status FROM action_history WHERE device_id=?",String.class,f.device()));
        assertEquals("binding:"+binding.id(),sql.queryForObject("SELECT target_key FROM current_desired_twin_state WHERE device_id=?",String.class,f.device()));assertEquals(0,count("current_desired_twin_state",scope));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id=?",Integer.class,f.rule().ruleId(),source));assertDirectOrOutbox();
    }
    @Test void actualEvaluatorPlannerEngineDenyVersionPauseAndABARaces() throws Exception {
        for(String change:List.of("version","pause","aba","stub")){
            var f=fixture();var captured=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            if(change.equals("stub")){
                var r=f.rule();lifecycle.legacySave(new Rule(r.ruleId(),"Stub race",true,1,r.conditions(),List.of(new RuleAction("CREATE_ALERT",Map.of()),new RuleAction("WEBHOOK",Map.of("url","https://example.invalid"))),Map.of()),null);
            }
            var pinned=versions.list(true).stream().filter(v->v.ruleId().equals(f.rule().ruleId())).findFirst().orElseThrow().definition();
            UUID source=UUID.randomUUID();
            org.mockito.Mockito.doAnswer(invocation->{var result=invocation.callRealMethod();if(((Rule)invocation.getArgument(0)).ruleId().equals(pinned.ruleId())){captured.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));}return result;}).when(evaluator).evaluate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.<com.nexus.rule.snapshot.RuleEvaluationContext>any());
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                var task=pool.submit(()->matcher.onEvent(new DomainEvent(source,EventType.TWIN_UPDATED,f.device(),Instant.now(),"race",Map.of("value",1),"INFO","pipeline-"+change)));
                assertTrue(captured.await(15,java.util.concurrent.TimeUnit.SECONDS));
                if(change.equals("version")){var view=versions.get(pinned.ruleId()).orElseThrow();lifecycle.draft(new Rule(pinned.ruleId(),"V2",false,1,pinned.conditions(),pinned.actions(),Map.of()),view.rowRevision());lifecycle.transition(pinned.ruleId(),"activate",null);}
                else {lifecycle.transition(pinned.ruleId(),"pause",null);if(change.equals("aba"))lifecycle.transition(pinned.ruleId(),"resume",null);}
                release.countDown();task.get(15,java.util.concurrent.TimeUnit.SECONDS);
                UUID match=sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=? AND event_id=?",UUID.class,pinned.ruleId(),source);
                var history=matchHistory.findById(match).orElseThrow();assertEquals(pinned.executionIdentity().ruleVersionId(),history.getRuleVersionId());assertEquals(pinned.executionIdentity().ruleRevision(),history.getRuleRevision());assertEquals(pinned.executionIdentity().executionEpoch(),history.getExecutionEpoch());assertEquals(pinned.name(),history.getRuleSnapshot().get("name"));assertFalse(sql.queryForObject("SELECT cooldown_eligible FROM rule_matches WHERE match_id=?",Boolean.class,match));
                noEffects(f.device());
            }finally{release.countDown();pool.shutdownNow();org.mockito.Mockito.reset(evaluator);}
        }
    }
    @Test void actualPipelineAcceptedBeforePauseMayFinishRealPublication() throws Exception {
        var f=fixture();var publishing=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        holdPublication(inv->{publishing.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));return inv.callRealMethod();});
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{var task=pool.submit(()->matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.device(),Instant.now(),"accepted-before-pause",Map.of(),"INFO","before-pause")));
            assertTrue(publishing.await(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,count("action_history",f.device()));assertEquals("ACCEPTED_FOR_DISPATCH",sql.queryForObject("SELECT status FROM action_history WHERE device_id=?",String.class,f.device()));
            lifecycle.transition(f.rule().ruleId(),"pause",null);release.countDown();task.get(15,java.util.concurrent.TimeUnit.SECONDS);
            awaitDelivery();assertEquals("SUCCESS",sql.queryForObject("SELECT status FROM action_history WHERE device_id=?",String.class,f.device()));assertEquals(1,count("current_desired_twin_state",f.device()));assertEquals(1,count("current_twin_verification_state",f.device()));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void positiveStubPipelineRequiresCommittedSourceAndMatch(){
        var f=fixture();var r=f.rule();lifecycle.legacySave(new Rule(r.ruleId(),"Positive stubs",true,1,r.conditions(),List.of(new RuleAction("CREATE_ALERT",Map.of()),new RuleAction("WEBHOOK",Map.of("url","https://example.invalid"))),Map.of()),null);
        UUID source=UUID.randomUUID();matcher.onEvent(new DomainEvent(source,EventType.TWIN_UPDATED,f.device(),Instant.now(),"stubs",Map.of(),"INFO","positive-stubs"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id=?",Integer.class,r.ruleId(),source));
        org.mockito.Mockito.verify(alerts).execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());org.mockito.Mockito.verify(webhooks).execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m ON a.match_id=m.match_id WHERE m.event_id=?",Integer.class,source));
    }
    @Test void sourceIdentityTypeAndCorrelationCannotAuthorizeNewEffects(){
        var f=fixture();
        for(var event:List.of(new DomainEvent(f.event().eventId(),EventType.TWIN_CREATED,f.device(),Instant.now(),"type",Map.of("value",99),"INFO",f.event().correlationId()),new DomainEvent(f.event().eventId(),EventType.TWIN_UPDATED,f.device(),Instant.now(),"correlation",Map.of("value",99),"INFO","other-correlation"))){matcher.onEvent(event);noEffects(f.device());}
        assertEquals("TWIN_UPDATED",sql.queryForObject("SELECT event_type FROM domain_events WHERE event_id=?",String.class,f.event().eventId()));assertEquals("p31-guard",sql.queryForObject("SELECT correlation_id FROM domain_events WHERE event_id=?",String.class,f.event().eventId()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.rule().ruleId()));
    }
    @Test void centralValidatorNegativeContractsNeverReplaceOwnerOrAcceptActions(){
        var f=fixture();var r=f.rule();var original=versions.get(r.ruleId()).orElseThrow();
        var number=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"bounded_"+UUID.randomUUID().toString().replace("-",""),"Bounded",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.NUMBER,"CELSIUS",List.of("CELSIUS","FAHRENHEIT"),java.math.BigDecimal.ZERO,new java.math.BigDecimal("100"),List.of(),List.of(),null,Map.of(),Map.of()));
        var booleanDef=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"boolean_"+UUID.randomUUID().toString().replace("-",""),"Boolean",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.BOOLEAN,null,List.of(),null,null,List.of(),List.of(),null,Map.of(),Map.of()));
        var enumDef=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"enum_"+UUID.randomUUID().toString().replace("-",""),"Enum",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.ENUM,null,List.of(),null,null,List.of("AUTO","MANUAL"),List.of(),null,Map.of(),Map.of()));
        for(var d:List.of(number,booleanDef,enumDef))bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,null,Map.of(),null));
        var disabled=readDefinition();bindings.create(f.device(),new BindingMutationRequest(disabled.getId(),false,"CELSIUS",Map.of(),null));
        var disabledWrite=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"disabled_write_"+UUID.randomUUID().toString().replace("-",""),"Disabled write",null,CapabilityDirection.WRITE,com.nexus.device.capability.definition.CapabilityValueType.NUMBER,null,List.of(),null,null,List.of(),List.of("SET"),null,Map.of(),Map.of()));bindings.create(f.device(),new BindingMutationRequest(disabledWrite.getId(),false,null,Map.of(),null));
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'RELAY','WRITE','BOOLEAN','{\"channels\":[1,2]}')",UUID.randomUUID(),f.device());bindings.create(f.device(),new BindingMutationRequest(sql.queryForObject("SELECT id FROM capability_definitions WHERE capability_key='relay'",UUID.class),true,null,Map.of(),null));
        var aliasA=readDefinition();var aliasB=readDefinition();for(var d:List.of(aliasA,aliasB))sql.update("INSERT INTO device_capability_bindings(id,device_id,capability_definition_id,enabled,unit,external_identifiers,created_at,updated_at) VALUES(?,?,?,true,'CELSIUS','{\"telemetryKey\":\"ambiguous_alias\"}',now(),now())",UUID.randomUUID(),f.device(),d.getId());
        var invalid=new ArrayList<Rule>();
        invalid.add(numericRule(f,"ambiguous_alias",null));
        for(var c:List.of(new RuleCondition(RuleField.VALUE,RuleOperator.GT,"-1",null,number.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"true",null,booleanDef.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"AUTO",null,enumDef.getKey(),null))){var conditions=new ArrayList<>(r.conditions());conditions.add(c);invalid.add(new Rule(r.ruleId(),"Invalid condition",false,1,conditions,r.actions(),Map.of()));}
        for(Object value:List.of(Map.of("channel",1.5,"state",true),Map.of("channel",0,"state",true),Map.of("channel",1),Map.of("channel",1,"state","on")))invalid.add(new Rule(r.ruleId(),"Invalid action",false,1,r.conditions(),List.of(new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capabilityKey","relay","command","SET_STATE","value",value))),Map.of()));
        for(var action:List.of(new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capabilityKey",disabledWrite.getKey(),"command","SET","value",1)),new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capabilityKey","relay","command","SET_STATE","value",Map.of("channel",1,"state",true))),new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capability","RELAY","command","SET_STATE","value",Map.of("channel",9,"state",true)))))invalid.add(new Rule(r.ruleId(),"Invalid action",false,1,r.conditions(),List.of(action),Map.of()));
        for(var c:List.of(new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"0",null,"missing_custom_binding",null),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",CapabilityType.TEMPERATURE),new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"0",null,disabled.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"true",null,"led",null),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"101",null,number.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"NaN",null,number.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"yes",null,booleanDef.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"BROKEN",null,enumDef.getKey(),null),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35",null,number.getKey(),"FAHRENHEIT"),new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"COMMAND_FAILED"),new RuleCondition(RuleField.DEVICE_ID,RuleOperator.NE,f.device().toString()),new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,UUID.randomUUID().toString()))){var conditions=new ArrayList<>(r.conditions());conditions.add(c);invalid.add(new Rule(r.ruleId(),"Invalid condition",false,1,conditions,r.actions(),Map.of()));}
        for(var action:List.of(new RuleAction("UNSUPPORTED_EXECUTOR",Map.of()),new RuleAction("SEND_COMMAND",Map.of("deviceId",UUID.randomUUID().toString(),"capability","LED","command","SET_STATE","value",true)),new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capability","LED","command","TOGGLE","value",true)),new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capability","LED","command","SET_STATE","value","on")),new RuleAction("SEND_COMMAND",Map.of("deviceId",f.device().toString(),"capabilityKey",number.getKey(),"command","SET_STATE","value",40))))invalid.add(new Rule(r.ruleId(),"Invalid action",false,1,r.conditions(),List.of(action),Map.of()));
        for(var definition:invalid){var before=versions.get(r.ruleId()).orElseThrow();var draft=lifecycle.draft(definition,before.rowRevision());var failed=lifecycle.transition(r.ruleId(),"activate",draft.rowRevision());assertFalse(failed.diagnostics().isEmpty(),definition.toString());assertTrue(failed.diagnostics().stream().allMatch(d->d.get("code")!=null&&d.get("path").toString().startsWith(definition.name().equals("Invalid action")?"actions[":"conditions[")),failed.diagnostics().toString());assertEquals(original.activeVersionId(),failed.activeVersionId());assertEquals(original.executionEpoch(),failed.executionEpoch());assertEquals(original.activeVersionId(),registry.listEnabledOrderedByPriority().stream().filter(rule->rule.ruleId().equals(r.ruleId())).findFirst().orElseThrow().executionIdentity().ruleVersionId());noEffects(f.device());}
    }
    @Test void activationFirstForcesBindingAndDefinitionMutationToWaitThenReject() throws Exception {
        for(boolean definitionMutation:new boolean[]{false,true}){
            var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);var d=readDefinition();var binding=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),null));
            var rule=numericRule(f,d.getKey(),"CELSIUS");var draft=lifecycle.draft(rule,versions.get(rule.ruleId()).orElseThrow().rowRevision());
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var task=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            try{new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->{
                var active=lifecycle.transition(rule.ruleId(),"activate",draft.rowRevision());assertEquals("ACTIVE",active.lifecycle());
                task.set(pool.submit(()->{if(definitionMutation)definitions.update(d.getId(),new com.nexus.device.capability.definition.api.UpdateCapabilityDefinitionRequest(d.getDisplayName(),d.getDescription(),d.getUnit(),d.getAllowedUnits(),d.getMinimum(),new java.math.BigDecimal("5"),d.getEnumValues(),d.getSupportedCommands(),d.getSemanticCategory(),d.getObjectConstraints(),d.getMetadata()));else bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,false,null,null,null));}));
                observeWait(definitionMutation?"capability_definitions WHERE id=":"device");return null;
            });
            var denied=assertThrows(java.util.concurrent.ExecutionException.class,()->task.get().get(15,java.util.concurrent.TimeUnit.SECONDS));assertTrue(denied.getCause() instanceof RuleMutationException||denied.getCause() instanceof IllegalStateException);
            assertEquals("ACTIVE",versions.get(rule.ruleId()).orElseThrow().lifecycle());assertTrue(sql.queryForObject("SELECT enabled FROM device_capability_bindings WHERE id=?",Boolean.class,binding.id()));assertNull(definitionRows.findById(d.getId()).orElseThrow().getMaximum());
            }finally{pool.shutdownNow();}
        }
    }
    @Test void overlappingTokenlessMutationsObserveOneRevisionAndCannotLoseAnEdit() throws Exception {
        var f=fixture();var before=versions.get(f.rule().ruleId()).orElseThrow();Rule immutableV1=versions.version(before.ruleId(),before.activeVersionId());
        var a=new Rule(before.ruleId(),"race-A",false,3,List.of(f.rule().conditions().getFirst(),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35")),
            List.of(f.rule().actions().getFirst(),new RuleAction("CREATE_ALERT",Map.of("severity","INFO"))),Map.of("owner","race-A","ordering","command-first"),30);
        var b=new Rule(before.ruleId(),"race-B",false,4,List.of(new RuleCondition(RuleField.VALUE,RuleOperator.GT,"45"),f.rule().conditions().getFirst()),
            List.of(new RuleAction("CREATE_ALERT",Map.of("severity","WARNING")),f.rule().actions().getFirst()),Map.of("owner","race-B","ordering","alert-first"),45);
        record Outcome(Rule submission,RuleVersionStore.View response,Integer conflict){}
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var tasks=new ArrayList<java.util.concurrent.Future<Outcome>>();
        try{new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->{sql.queryForList("SELECT id FROM devices WHERE id=? FOR UPDATE",f.device());for(Rule submitted:List.of(a,b))tasks.add(pool.submit(()->{start.await();try{return new Outcome(submitted,lifecycle.legacySave(submitted,null),null);}catch(RuleMutationException stale){return new Outcome(submitted,null,stale.getStatusCode().value());}}));start.countDown();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean waiting=false;while(System.nanoTime()<deadline){sql.execute("SELECT pg_stat_clear_snapshot()");if(sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND datname=current_database() AND wait_event_type='Lock' AND query LIKE '%devices WHERE id=%'",Integer.class)>=2){waiting=true;break;}}assertTrue(waiting,"Both requests captured the same server-observed revision before device lock release");return null;});
            var outcomes=List.of(tasks.get(0).get(15,java.util.concurrent.TimeUnit.SECONDS),tasks.get(1).get(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter(o->o.response()!=null).count());assertEquals(1,outcomes.stream().filter(o->o.conflict()!=null).count());
            var winner=outcomes.stream().filter(o->o.response()!=null).findFirst().orElseThrow();var loser=outcomes.stream().filter(o->o.conflict()!=null).findFirst().orElseThrow();assertEquals(409,loser.conflict());
            var durable=versions.get(before.ruleId()).orElseThrow();assertEquals(winner.response(),durable);assertEquals(winner.response().workingVersionId(),durable.workingVersionId());assertEquals(winner.submission(),versions.version(before.ruleId(),durable.workingVersionId()));assertTrue(versions.sameDefinition(winner.submission(),durable.definition()));assertFalse(versions.sameDefinition(loser.submission(),durable.definition()));
            assertEquals(List.of(1L,2L),sql.queryForList("SELECT revision FROM rule_versions WHERE rule_id=? ORDER BY revision",Long.class,before.ruleId()));assertEquals(2,versions.history(before.ruleId()).size());assertEquals(2L,((Number)versions.history(before.ruleId()).getLast().get("revision")).longValue());assertEquals(durable.workingVersionId(),versions.history(before.ruleId()).getLast().get("versionId"));
            assertEquals(before.rowRevision()+1,durable.rowRevision());assertEquals(before.activeVersionId(),durable.activeVersionId());assertEquals(immutableV1,versions.version(before.ruleId(),durable.activeVersionId()));
            assertEquals("PAUSED",durable.lifecycle());assertEquals(before.executionEpoch()+1,durable.executionEpoch(),"Legacy enabled=false composite PUT pauses, unlike a modern draft-only edit");
            assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE rule_versions SET definition='{}' WHERE version_id=?",durable.workingVersionId()));assertEquals(winner.submission(),versions.version(before.ruleId(),durable.workingVersionId()));noEffects(f.device());
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void pauseBeforeAcceptanceProducesNoIntentHistoryOrVerification(){
        var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);var request=request(f);
        assertEquals("RULE_NOT_ACTIVE",assertThrows(RuleExecutionSuppressedException.class,()->acceptance.accept(request,new ActionContext(f.event(),f.rule()),Instant.now())).getMessage());empty(f,request);
    }
    @Test void pauseResumeABARejectsOldEpochDespiteSameVersion(){
        var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);lifecycle.transition(f.rule().ruleId(),"resume",null);var request=request(f);
        assertEquals("STALE_VERSION_OR_EPOCH",assertThrows(RuleExecutionSuppressedException.class,()->acceptance.accept(request,new ActionContext(f.event(),f.rule()),Instant.now())).getMessage());empty(f,request);
    }
    @Test void versionSwitchAllowsOldHistoryButNoOldAcceptance(){
        var f=fixture();var view=versions.get(f.rule().ruleId()).orElseThrow();var r=f.rule();
        lifecycle.draft(new Rule(r.ruleId(),"V2",false,r.priority(),r.conditions(),r.actions(),r.metadata()),view.rowRevision());lifecycle.transition(r.ruleId(),"activate",null);
        UUID delayed=UUID.randomUUID();matches.persist(new RuleMatchedEvent(delayed,Instant.now(),new RuleEvaluationResult(true,r,f.event(),"Delayed V1"),"p31-delayed"));
        assertEquals(r.executionIdentity().ruleVersionId(),matchHistory.findById(delayed).orElseThrow().getRuleVersionId());
        assertFalse(sql.queryForObject("SELECT cooldown_eligible FROM rule_matches WHERE match_id=?",Boolean.class,delayed));
        var request=request(f);assertThrows(RuleExecutionSuppressedException.class,()->acceptance.accept(request,new ActionContext(f.event(),r),Instant.now()));empty(f,request);
    }
    @Test void alreadyDurablyAcceptedCommandMayPublishAfterPause(){
        var f=fixture();var request=request(f);var prepared=acceptance.accept(request,new ActionContext(f.event(),f.rule()),Instant.now());
        lifecycle.transition(f.rule().ruleId(),"pause",null);
        publishAcceptedAfterPause(request,prepared);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM action_history WHERE request_id=?",Integer.class,request.requestId()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM current_desired_twin_state WHERE command_id=?",Integer.class,request.requestId()));
    }
    @Test void missingDurableMatchPreventsNewAcceptance(){
        var f=fixture();var request=new ActionExecutionRequest(UUID.randomUUID(),UUID.randomUUID(),ActionType.SEND_COMMAND,f.rule().actions().getFirst().parameters());
        assertThrows(RuleExecutionSuppressedException.class,()->acceptance.accept(request,new ActionContext(f.event(),f.rule()),Instant.now()));empty(f,request);
    }
    @Test void disabledBindingCannotFallBackToInstalledLegacyTarget(){
        var f=fixture();
        // Deliberate out-of-band contract invalidation: the supported API rejects this.
        sql.update("INSERT INTO device_capability_bindings(id,device_id,capability_definition_id,enabled,configuration,external_identifiers,created_at,updated_at) VALUES(?,?,?,false,'{}','{\"commandCapability\":\"LED\"}',now(),now())",UUID.randomUUID(),f.device(),sql.queryForObject("SELECT id FROM capability_definitions WHERE capability_key='led'",UUID.class));
        var request=request(f);assertThrows(RuleExecutionSuppressedException.class,()->acceptance.accept(request,new ActionContext(f.event(),f.rule()),Instant.now()));empty(f,request);
    }
    @Test void databaseGuardProtectsCrossDeviceLegacyActionTarget(){
        var f=fixture();UUID scope=device();var view=versions.get(f.rule().ruleId()).orElseThrow();var r=f.rule();
        lifecycle.legacySave(new Rule(r.ruleId(),"Cross device",true,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,scope.toString())),r.actions(),Map.of()),view.rowRevision());
        assertTrue(guard.references(f.device(),"led"));
        assertThrows(RuleMutationException.class,()->capabilities.deleteCapability(f.device(),f.capability()));
    }
    @Test void sourcePersistenceFailureStopsActualLegacyPipelineBeforeAnyPublication(){
        var f=fixture();UUID source=UUID.randomUUID();
        failureActions(f);
        sql.execute("CREATE FUNCTION p31_fail_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id='"+source+"'::uuid THEN RAISE EXCEPTION 'source persistence injected failure'; END IF; RETURN NEW; END $$");
        sql.execute("CREATE TRIGGER p31_fail_source BEFORE INSERT ON domain_events FOR EACH ROW EXECUTE FUNCTION p31_fail_source()");
        try{
            matcher.onEvent(new DomainEvent(source,EventType.TWIN_UPDATED,f.device(),Instant.now(),"test",Map.of("value",1),"INFO","source-failure"));
            noEffects(f.device());assertEquals(0,sql.queryForObject("SELECT count(*) FROM domain_events WHERE event_id=?",Integer.class,source));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE event_id=?",Integer.class,source));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.rule().ruleId()));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m USING(match_id) WHERE m.rule_id=?",Integer.class,f.rule().ruleId()));
            org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        }finally{sql.execute("DROP TRIGGER p31_fail_source ON domain_events");sql.execute("DROP FUNCTION p31_fail_source()");}
    }
    @Test void matchPersistenceFailureStopsActualLegacyPipelineBeforeAnyPublication(){
        var f=fixture();UUID source=UUID.randomUUID();
        failureActions(f);
        sql.execute("CREATE FUNCTION p31_fail_match() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.rule_id='"+f.rule().ruleId()+"'::uuid THEN RAISE EXCEPTION 'match persistence injected failure'; END IF; RETURN NEW; END $$");
        sql.execute("CREATE TRIGGER p31_fail_match BEFORE INSERT ON rule_matches FOR EACH ROW EXECUTE FUNCTION p31_fail_match()");
        try{
            matcher.onEvent(new DomainEvent(source,EventType.TWIN_UPDATED,f.device(),Instant.now(),"test",Map.of("value",1),"INFO","match-failure"));
            noEffects(f.device());assertEquals(1,sql.queryForObject("SELECT count(*) FROM domain_events WHERE event_id=?",Integer.class,source));assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE event_id=?",Integer.class,source));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.rule().ruleId()));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m USING(match_id) WHERE m.rule_id=?",Integer.class,f.rule().ruleId()));
            org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        }finally{sql.execute("DROP TRIGGER p31_fail_match ON rule_matches");sql.execute("DROP FUNCTION p31_fail_match()");}
    }
    private void failureActions(Fixture f){var r=f.rule();var actions=new ArrayList<>(r.actions());actions.add(new RuleAction("CREATE_ALERT",Map.of()));actions.add(new RuleAction("WEBHOOK",Map.of("url","https://example.invalid")));lifecycle.legacySave(new Rule(r.ruleId(),"All supported effects",true,1,r.conditions(),actions,Map.of()),null);}
    @Test void genericConditionsStayInDeviceOrTriggerScopeWithoutMissingScopeExecution(){
        var f=fixture();var d=readDefinition();var own=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),null));reported(f.device(),own.id(),d);
        UUID other=device();var alternate=bindings.create(other,new BindingMutationRequest(d.getId(),true,"CELSIUS",Map.of(),null));reported(other,alternate.id(),d);
        var scoped=numericRule(f,d.getKey(),null);lifecycle.legacySave(new Rule(scoped.ruleId(),scoped.name(),true,1,scoped.conditions(),scoped.actions(),Map.of()),null);
        assertFalse(evaluator.evaluate(scoped,new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,other,Instant.now(),"scope",Map.of(),"INFO","scope")).matched());
        var global=new Rule(scoped.ruleId(),"Trigger device",true,1,List.of(scoped.conditions().get(1)),scoped.actions(),Map.of());lifecycle.legacySave(global,null);
        var result=evaluator.evaluate(global,new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,other,Instant.now(),"scope",Map.of(),"INFO","scope"));assertTrue(result.matched());assertEquals(alternate.id().toString(),result.evidence().getFirst().bindingId().toString());
        UUID independent=device();var unrelated=readDefinition();assertDoesNotThrow(()->bindings.create(independent,new BindingMutationRequest(unrelated.getId(),true,"CELSIUS",Map.of(),null)),"A global reference must not protect an unrelated contract on an uninstalled scope");
        matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,null,Instant.now(),"missing-scope",Map.of(),"INFO","missing-scope"));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,global.ruleId()));noEffects(f.device());
    }
    @SuppressWarnings("unchecked") @Test void stalePresentationAndFailedDatabaseReadCannotAuthorizePipeline(){
        var f=fixture();var cache=(Map<UUID,Rule>)org.springframework.test.util.ReflectionTestUtils.getField(registry,"rules");
        lifecycle.transition(f.rule().ruleId(),"pause",null);cache.put(f.rule().ruleId(),f.rule());
        matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.device(),Instant.now(),"stale-cache",Map.of(),"INFO","stale-cache"));noEffects(f.device());assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.rule().ruleId()));
        lifecycle.transition(f.rule().ruleId(),"resume",null);cache.put(f.rule().ruleId(),f.rule());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Injected execution repository outage")).when(versions).list(true);
        assertThrows(org.springframework.dao.DataAccessException.class,()->matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.device(),Instant.now(),"read-outage",Map.of(),"INFO","read-outage")));noEffects(f.device());assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,f.rule().ruleId()));
    }
    @Test void typedDevicePredicateCannotHijackDeclaredScopeOrActivate(){
        var f=fixture();var d=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"scope_identity_"+UUID.randomUUID().toString().replace("-",""),"Identity",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.STRING,null,List.of(),null,null,List.of(),List.of(),null,Map.of(),Map.of()));
        var binding=bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,null,Map.of(),null));
        twins.put(new com.nexus.twin.domain.DigitalTwin(f.device(),Instant.now(),d.getKey(),f.device().toString(),null,Instant.now(),Instant.now(),Map.of(),d.getId(),binding.id(),d.getKey(),d.getValueType(),com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING));
        var invalid=new Rule(f.rule().ruleId(),"Typed DEVICE_ID",false,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,f.device().toString(),null,d.getKey(),null)),f.rule().actions(),Map.of());
        var before=versions.get(invalid.ruleId()).orElseThrow();var draft=lifecycle.draft(invalid,before.rowRevision());var refused=lifecycle.transition(invalid.ruleId(),"activate",draft.rowRevision());
        assertFalse(refused.diagnostics().isEmpty());assertEquals("conditions[0]",refused.diagnostics().getFirst().get("path"));assertEquals(before.activeVersionId(),refused.activeVersionId());assertEquals(before.executionEpoch(),refused.executionEpoch());
        assertFalse(evaluator.evaluate(invalid,new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,device(),Instant.now(),"unrelated-device",Map.of(),"INFO","scope-hijack")).matched());noEffects(f.device());
    }
    @Test void committedEligibleMatchRetainsCooldownWhenOwnerChangesBeforeTimestampHandoff() throws Exception {
        Instant time=Instant.parse("2026-10-13T12:00:00Z");org.mockito.Mockito.when(clock.instant()).thenReturn(time);
        var f=fixture();var r=f.rule();lifecycle.legacySave(new Rule(r.ruleId(),"Cooldown handoff",true,1,r.conditions(),r.actions(),Map.of(),60),null);
        // The fixture's own committed match is OLDER than the cooldown window of this scenario (its wall-clock stamp is unrelated to the injected clock).
        sql.update("UPDATE rule_matches SET matched_at=? WHERE rule_id=?",java.sql.Timestamp.from(time.minusSeconds(3600)),r.ruleId());
        sql.update("UPDATE rule_cooldown_state SET last_matched_at=? WHERE rule_id=?",java.sql.Timestamp.from(time.minusSeconds(3600)),r.ruleId());
        var committed=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv->{var match=(RuleMatchedEvent)inv.getArgument(0);if(match.result().rule().ruleId().equals(r.ruleId())&&once.compareAndSet(false,true)){
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){committed.countDown();try{assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}});
        }return inv.callRealMethod();}).when(matches).persist(org.mockito.ArgumentMatchers.any());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{var task=pool.submit(()->matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.device(),time,"handoff",Map.of(),"INFO","handoff")));
            assertTrue(committed.await(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(2,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,r.ruleId()));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=? AND matched_at=? AND cooldown_eligible",Integer.class,r.ruleId(),java.sql.Timestamp.from(time)));
            lifecycle.transition(r.ruleId(),"pause",null);lifecycle.transition(r.ruleId(),"resume",null);release.countDown();task.get(15,java.util.concurrent.TimeUnit.SECONDS);noEffects(f.device());
            matcher.onEvent(new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,f.device(),time,"after-resume",Map.of(),"INFO","after-resume"));
            assertEquals(2,sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,r.ruleId()),"A legitimately committed match must preserve the logical cooldown despite an intervening epoch change");noEffects(f.device());
            assertEquals(time,sql.queryForObject("SELECT last_matched_at FROM rule_cooldown_state WHERE rule_id=? AND scope_key=''",java.sql.Timestamp.class,r.ruleId()).toInstant(),"the durable authority holds the committed match");
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void manualAcceptanceRequiresNoInventedRuleIdentity(){
        var f=fixture();var request=new ActionExecutionRequest(UUID.randomUUID(),null,ActionType.SEND_COMMAND,f.rule().actions().getFirst().parameters());
        assertNotNull(acceptance.accept(request,new ActionContext(null,null),Instant.now()));
        assertEquals("MANUAL_COMMAND",sql.queryForObject("SELECT source FROM current_desired_twin_state WHERE command_id=?",String.class,request.requestId()));
    }
    @Test void reusedSourceIdCannotSubstituteUnpersistedPayload(){
        var f=fixture();var r=f.rule();
        lifecycle.legacySave(new Rule(r.ruleId(),"Persisted value only",true,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,f.device().toString()),new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"2")),r.actions(),Map.of()),null);
        matcher.onEvent(new DomainEvent(f.event().eventId(),EventType.TWIN_UPDATED,f.device(),f.event().timestamp(),"test",Map.of("value",2),"INFO","p31-guard"));
        empty(f,request(f));assertEquals("1",sql.queryForObject("SELECT payload->>'value' FROM domain_events WHERE event_id=?",String.class,f.event().eventId()));
        org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void reusedSourceIdWithDifferentScopeCannotAuthorize(){
        var f=fixture();
        matcher.onEvent(new DomainEvent(f.event().eventId(),EventType.TWIN_UPDATED,device(),f.event().timestamp(),"test",Map.of("value",1),"INFO","p31-guard"));
        empty(f,request(f));org.mockito.Mockito.verify(executor,org.mockito.Mockito.never()).publishPrepared(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    private void observeWait(String fragment){
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do{sql.execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,"%"+fragment+"%")))return;}while(System.nanoTime()<deadline);
        fail("Expected real PostgreSQL lock contention: "+fragment);
    }
    @Test void bindingDisableFirstMakesWaitingActivationFailClosed() throws Exception {
        var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);
        var binding=bindings.create(f.device(),new BindingMutationRequest(sql.queryForObject("SELECT id FROM capability_definitions WHERE capability_key='led'",UUID.class),true,null,Map.of(),null));
        var before=versions.get(f.rule().ruleId()).orElseThrow();var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var task=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<RuleVersionStore.View>>();
        try{
            new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->{
                sql.queryForList("SELECT id FROM devices WHERE id=? FOR UPDATE",f.device());
                task.set(pool.submit(()->lifecycle.transition(f.rule().ruleId(),"activate",before.rowRevision())));
                observeWait("devices WHERE id=");
                bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,false,null,null,null));return null;
            });
            var result=task.get().get(15,java.util.concurrent.TimeUnit.SECONDS);assertEquals("PAUSED",result.lifecycle());assertEquals(before.executionEpoch(),result.executionEpoch());assertFalse(result.diagnostics().isEmpty());
        }finally{pool.shutdownNow();}
    }
    @Test void activationFirstProtectsBindingAgainstLaterDisable(){
        var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);
        var binding=bindings.create(f.device(),new BindingMutationRequest(sql.queryForObject("SELECT id FROM capability_definitions WHERE capability_key='led'",UUID.class),true,null,Map.of(),null));
        lifecycle.transition(f.rule().ruleId(),"activate",null);
        assertThrows(IllegalStateException.class,()->bindings.update(f.device(),binding.id(),new BindingMutationRequest(null,false,null,null,null)));
        assertTrue(sql.queryForObject("SELECT enabled FROM device_capability_bindings WHERE id=?",Boolean.class,binding.id()));
    }
    @Test void resumeRechecksChangedDefinitionEvenForLegacyCommandSyntax(){
        versions.list(true).forEach(v->lifecycle.transition(v.ruleId(),"pause",null));
        var f=fixture();var paused=lifecycle.transition(f.rule().ruleId(),"pause",null);
        var d=definitionRows.findByKey("led").orElseThrow();
        try{
            definitions.update(d.getId(),new com.nexus.device.capability.definition.api.UpdateCapabilityDefinitionRequest(d.getDisplayName(),d.getDescription(),d.getUnit(),d.getAllowedUnits(),d.getMinimum(),d.getMaximum(),d.getEnumValues(),List.of(),d.getSemanticCategory(),d.getObjectConstraints(),d.getMetadata()));
            var refused=lifecycle.transition(f.rule().ruleId(),"resume",paused.rowRevision());
            assertEquals("PAUSED",refused.lifecycle());assertEquals(paused.executionEpoch(),refused.executionEpoch());assertFalse(refused.diagnostics().isEmpty());
        }finally{
            definitions.update(d.getId(),new com.nexus.device.capability.definition.api.UpdateCapabilityDefinitionRequest(d.getDisplayName(),d.getDescription(),d.getUnit(),d.getAllowedUnits(),d.getMinimum(),d.getMaximum(),d.getEnumValues(),d.getSupportedCommands(),d.getSemanticCategory(),d.getObjectConstraints(),d.getMetadata()));
        }
    }
    @Test void definitionMutationFirstIsSeenByWaitingActivation() throws Exception {
        var f=fixture();lifecycle.transition(f.rule().ruleId(),"pause",null);
        var d=definitionRows.save(new com.nexus.device.capability.definition.CapabilityDefinition(UUID.randomUUID(),"p31_numeric_"+UUID.randomUUID().toString().replace("-",""),"Numeric",null,CapabilityDirection.READ,com.nexus.device.capability.definition.CapabilityValueType.NUMBER,null,List.of(),java.math.BigDecimal.ZERO,new java.math.BigDecimal("100"),List.of(),List.of(),"test",Map.of(),Map.of()));
        bindings.create(f.device(),new BindingMutationRequest(d.getId(),true,null,Map.of(),null));
        var r=f.rule();var before=lifecycle.legacySave(new Rule(r.ruleId(),"Definition race",false,1,List.of(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,f.device().toString()),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"10",null,d.getKey(),null)),r.actions(),Map.of()),null);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var task=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<RuleVersionStore.View>>();
        try{
            new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->{
                sql.queryForList("SELECT id FROM capability_definitions WHERE id=? FOR UPDATE",d.getId());
                task.set(pool.submit(()->lifecycle.transition(r.ruleId(),"activate",before.rowRevision())));observeWait("capability_definitions ORDER BY id FOR SHARE");
                definitions.update(d.getId(),new com.nexus.device.capability.definition.api.UpdateCapabilityDefinitionRequest(d.getDisplayName(),d.getDescription(),d.getUnit(),d.getAllowedUnits(),d.getMinimum(),new java.math.BigDecimal("5"),d.getEnumValues(),d.getSupportedCommands(),d.getSemanticCategory(),d.getObjectConstraints(),d.getMetadata()));return null;
            });
            var result=task.get().get(15,java.util.concurrent.TimeUnit.SECONDS);assertEquals("PAUSED",result.lifecycle());assertEquals(before.executionEpoch(),result.executionEpoch());assertFalse(result.diagnostics().isEmpty());
        }finally{pool.shutdownNow();}
    }
}
