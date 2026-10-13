package com.nexus.rule.application;

import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.*;
import com.nexus.action.domain.*;
import com.nexus.rule.api.RuleLifecycleController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RuleFoundationIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
        r.add("spring.flyway.url",db::getJdbcUrl);r.add("spring.flyway.user",db::getUsername);r.add("spring.flyway.password",db::getPassword);
        r.add("nexus.rule.reconciliation-delay-ms",()->"86400000");
    }
    @Autowired RuleLifecycleService lifecycle;
    @Autowired RuleVersionStore versions;
    @Autowired RuleExecutionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate sql;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired RuleRegistry registry;
    @Autowired RuleMatchPersister matchWriter;
    @Autowired RuleMatchRepository matchRows;
    @Autowired com.nexus.event.persistence.DomainEventPersister sourceWriter;
    @Autowired com.nexus.rule.ai.RuleMatchExplanationContextBuilder explanations;
    @Autowired com.nexus.rule.ai.AiExplanationContextMapper aiMapper;
    @org.springframework.boot.test.mock.mockito.MockBean java.time.Clock clock;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.device.capability.definition.CapabilityDefinitionRepository definitions;
    @org.junit.jupiter.api.BeforeEach void time(){org.mockito.Mockito.when(clock.instant()).thenReturn(java.time.Instant.now());}
    @Test void modernApiPreconditionsAndArchiveResponseAreTruthful() throws Exception {
        UUID id=UUID.randomUUID();var view=lifecycle.legacySave(rule(id,"HTTP tokens",true),null);String path="/api/v1/rules/"+id+"/lifecycle/pause";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(428));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"expectedRevision\":0}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("If-Match","\"0\"").contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("If-Match","\"1\"").contentType("application/json").content("{\"expectedRevision\":2}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+id+"/lifecycle/archive").contentType("application/json").content("{\"expectedRevision\":"+view.rowRevision()+"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }
    @Test void versionValidationFailureReturnsStructuredDetailsAfterDurableAttempt() throws Exception {
        UUID id=UUID.randomUUID();var root=lifecycle.createDraft(new Rule(id,"Incomplete",false,0,List.of(),List.of(),Map.of()));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+id+"/lifecycle/validate").contentType("application/json").content("{\"expectedRevision\":"+root.rowRevision()+"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details[0].code").exists());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=? AND version_id=? AND valid=false",Integer.class,id,root.workingVersionId()));
        assertEquals("DRAFT",versions.get(id).orElseThrow().lifecycle());
    }
    private Rule rule(UUID id,String name,boolean active){return new Rule(id,name,active,1,List.of(new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"TWIN_UPDATED")),List.of(new RuleAction("CREATE_ALERT",Map.of("severity","INFO"))),Map.of(),10);}
    @Test void legacyHttpCompositeEditsDisableEnableArchiveAndMissingIdsRemainCompatible() throws Exception {
        UUID id=UUID.randomUUID();var first=lifecycle.legacySave(rule(id,"V1",true),null);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(rule(id,"V2",true)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var second=versions.get(id).orElseThrow();assertNotEquals(first.activeVersionId(),second.activeVersionId());assertEquals(second.workingVersionId(),second.activeVersionId());assertEquals("VALIDATED",second.workingState());assertEquals("V2",versions.version(id,second.activeVersionId()).name());
        var invalid=new Rule(id,"Invalid PUT",true,1,second.definition().conditions(),List.of(new RuleAction("NOT_SUPPORTED",Map.of())),Map.of());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details[0].path").value("actions[0]"));
        assertEquals(second,versions.get(id).orElseThrow());assertEquals(2,versions.history(id).size());
        var disabled=rule(id,"Disabled edited V3",false);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(disabled))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var paused=versions.get(id).orElseThrow();assertEquals("PAUSED",paused.lifecycle());assertNotEquals(paused.activeVersionId(),paused.workingVersionId());assertEquals("Disabled edited V3",versions.version(id,paused.workingVersionId()).name());assertEquals(3,versions.history(id).size());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(disabled))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());assertEquals(3,versions.history(id).size());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+id+"/enable")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());var enabled=versions.get(id).orElseThrow();assertEquals(paused.workingVersionId(),enabled.activeVersionId());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+id+"/disable")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());assertEquals(enabled.executionEpoch()+1,versions.get(id).orElseThrow().executionEpoch());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/rules/"+id)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rules/"+id)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());assertTrue(registry.list().stream().noneMatch(r->r.ruleId().equals(id)));
        for(UUID missing:List.of(id,UUID.randomUUID()))for(String operation:List.of("enable","disable"))http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+missing+"/"+operation)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals("ARCHIVED",sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?",String.class,id));assertEquals(3,versions.history(id).size());
    }
    @Test void modernDraftPreconditionsFutureConfigurationAndNullableFilteringAreSafe() throws Exception {
        var view=lifecycle.createDraft(rule(UUID.randomUUID(),"Draft",false));String path="/api/v1/rules/"+view.ruleId()+"/draft";String definition=mapper.writeValueAsString(rule(view.ruleId(),"Edited",false));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"definition\":"+definition+"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(428));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"definition\":"+definition+",\"expectedRevision\":99}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("If-Match","\"99\"").contentType("application/json").content("{\"definition\":"+definition+"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("If-Match","\"0\"").contentType("application/json").content("{\"definition\":"+definition+",\"expectedRevision\":1}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());assertEquals(1,versions.history(view.ruleId()).size());
        for(String key:List.of("triggers","temporal","repeatPolicy","approvalPolicy"))http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules").contentType("application/json").content(definition.substring(0,definition.length()-1)+",\""+key+"\":{}}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        lifecycle.createDraft(new Rule(UUID.randomUUID(),"Placeholder",false,1,java.util.Arrays.asList((RuleCondition)null),java.util.Arrays.asList((RuleAction)null),Map.of()));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rules").param("deviceId",UUID.randomUUID().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    @Test void newDraftRevisionPreconditionsAndSuccessfulVersionCreationAreTruthful() throws Exception {
        String definition=mapper.writeValueAsString(rule(UUID.randomUUID(),"New draft",false));String path="/api/v1/rules/drafts";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"definition\":"+definition+"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(428));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"definition\":"+definition+",\"expectedRevision\":1}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("If-Match","\"1\"").contentType("application/json").content("{\"definition\":"+definition+"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("If-Match","\"0\"").contentType("application/json").content("{\"definition\":"+definition+",\"expectedRevision\":1}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"definition\":"+definition+",\"expectedRevision\":0}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rowRevision").value(0)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.lifecycle").value("DRAFT")).andReturn();
        UUID id=UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).get("ruleId").asText());assertEquals(1,versions.history(id).size());assertNull(versions.get(id).orElseThrow().activeVersionId());
    }
    @SuppressWarnings("unchecked") private Map<UUID,Rule> projection(){return (Map<UUID,Rule>)org.springframework.test.util.ReflectionTestUtils.getField(registry,"rules");}
    @Test void projectionFollowsCommitAndNeverLeaksRolledBackActivation(){
        UUID id=UUID.randomUUID();var original=lifecycle.legacySave(rule(id,"Committed V1",true),null);assertEquals("Committed V1",projection().get(id).name());
        new TransactionTemplate(transactions).execute(s->{lifecycle.legacySave(rule(id,"Rolled back V2",true),original.rowRevision());assertEquals("Committed V1",projection().get(id).name());s.setRollbackOnly();return null;});
        assertEquals(original,versions.get(id).orElseThrow());assertEquals("Committed V1",projection().get(id).name());assertEquals(1,versions.history(id).size());
        lifecycle.legacySave(rule(id,"Committed V2",true),original.rowRevision());assertEquals("Committed V2",projection().get(id).name());
    }
    @Test void archivedHistoryAndExplanationUseExactPinnedAndUnknownHistoricalFacts() throws Exception {
        UUID id=UUID.randomUUID();lifecycle.legacySave(rule(id,"Historical V1",true),null);
        Rule pinned=registry.listEnabledOrderedByPriority().stream().filter(r->r.ruleId().equals(id)).findFirst().orElseThrow();
        var event=sourceWriter.persistRequired(new com.nexus.event.domain.DomainEvent(UUID.randomUUID(),com.nexus.event.domain.EventType.TWIN_UPDATED,null,java.time.Instant.now(),"history",Map.of("value",40),"INFO","history"));
        UUID match=UUID.randomUUID();matchWriter.persist(new RuleMatchedEvent(match,java.time.Instant.now(),new RuleEvaluationResult(true,pinned,event,"Historical evidence",List.of(new ConditionEvidence(RuleField.VALUE,RuleOperator.GT,"35",40,true))),"history"));
        UUID unknown=UUID.randomUUID();sql.update("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,evaluation_evidence,matched_at) VALUES(?,?,'{\"name\":\"Unknown old version\"}',?,'{\"outcome\":\"matched\"}',now())",unknown,id,event.eventId());
        var before=matchRows.findById(match).orElseThrow();String snapshot=sql.queryForObject("SELECT rule_snapshot::text FROM rule_matches WHERE match_id=?",String.class,match),evidence=sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE match_id=?",String.class,match);
        assertEquals(pinned.executionIdentity().ruleVersionId(),before.getRuleVersionId());assertEquals(pinned.executionIdentity().ruleRevision(),before.getRuleRevision());assertEquals(pinned.executionIdentity().executionEpoch(),before.getExecutionEpoch());
        lifecycle.legacySave(rule(id,"Current replacement V2",true),null);lifecycle.transition(id,"archive",null);
        assertEquals(snapshot,sql.queryForObject("SELECT rule_snapshot::text FROM rule_matches WHERE match_id=?",String.class,match));assertEquals(evidence,sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE match_id=?",String.class,match));
        var context=explanations.build(match);assertEquals("Historical V1",context.ruleSnapshot().get("name"));assertEquals(before.getEvaluationEvidence(),context.evaluationEvidence());assertFalse(aiMapper.toAiContext(context).ruleSnapshot().containsKey("executionIdentity"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rule-matches/history/"+match)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ruleVersionId").value(pinned.executionIdentity().ruleVersionId().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ruleSnapshot.name").value("Historical V1"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rule-matches/history/"+unknown)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ruleVersionId").isEmpty());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rules/"+id+"/versions")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2));
    }
    @Test void legacyEnableRevalidatesWorkingSuccessorAndCannotSilentlyChooseOldActive() throws Exception {
        UUID id=UUID.randomUUID();var v1=lifecycle.legacySave(rule(id,"V1",true),null);
        var invalid=new Rule(id,"Invalid intended successor",false,1,v1.definition().conditions(),List.of(new RuleAction("UNSUPPORTED",Map.of())),Map.of());var draft=lifecycle.draft(invalid,v1.rowRevision());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+id+"/disable")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());var paused=versions.get(id).orElseThrow();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/"+id+"/enable")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details[0].path").value("actions[0]"));
        var failed=versions.get(id).orElseThrow();assertEquals("PAUSED",failed.lifecycle());assertEquals(v1.activeVersionId(),failed.activeVersionId());assertEquals(draft.workingVersionId(),failed.workingVersionId());assertEquals(paused.executionEpoch(),failed.executionEpoch());assertEquals(paused.rowRevision(),failed.rowRevision());assertFalse(projection().get(id).enabled());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=? AND version_id=? AND valid=false",Integer.class,id,draft.workingVersionId()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM action_history a JOIN rule_matches m USING(match_id) WHERE m.rule_id=?",Integer.class,id));
    }
    @Test void validationAndActivationRecordExactVersionPurposeAndCurrentClock(){
        UUID id=UUID.randomUUID();java.time.Instant now=java.time.Instant.parse("2026-10-13T06:00:00Z");org.mockito.Mockito.when(clock.instant()).thenReturn(now);
        var draft=lifecycle.createDraft(rule(id,"Specific version",false));var validated=lifecycle.transition(id,"validate",draft.rowRevision());assertEquals("VALIDATED",validated.lifecycle());assertEquals("VALIDATED",validated.workingState());assertNull(validated.activeVersionId());assertEquals(0,validated.executionEpoch());
        assertEquals(draft.workingVersionId(),sql.queryForObject("SELECT version_id FROM rule_validation_attempts WHERE rule_id=? AND purpose='VALIDATE' AND valid=true",UUID.class,id));assertEquals(now,sql.queryForObject("SELECT validated_at FROM rule_validation_attempts WHERE rule_id=?",java.sql.Timestamp.class,id).toInstant());
        var active=lifecycle.transition(id,"activate",validated.rowRevision());assertEquals(draft.workingVersionId(),active.activeVersionId());assertEquals(1,active.executionEpoch());assertEquals(2,active.rowRevision());assertEquals(1,versions.history(id).size());assertEquals(draft.workingVersionId(),sql.queryForObject("SELECT version_id FROM rule_validation_attempts WHERE rule_id=? AND purpose='ACTIVATE' AND valid=true",UUID.class,id));assertEquals("Specific version",projection().get(id).name());
    }
    @Test void malformedDraftCanBeValidatedRejectedRepairedAndArchived(){
        UUID id=UUID.randomUUID();
        var incomplete=new Rule(id,"Incomplete",false,0,java.util.Arrays.asList((RuleCondition)null),java.util.Arrays.asList((RuleAction)null),Map.of());
        var created=lifecycle.createDraft(incomplete);
        var invalid=lifecycle.transition(id,"validate",created.rowRevision());assertEquals("DRAFT",invalid.lifecycle());assertFalse(invalid.diagnostics().isEmpty());
        var repaired=lifecycle.draft(rule(id,"Repaired",false),invalid.rowRevision());
        var valid=lifecycle.transition(id,"validate",repaired.rowRevision());assertEquals("VALIDATED",valid.lifecycle());
        lifecycle.transition(id,"archive",valid.rowRevision());assertTrue(versions.get(id).isEmpty());
    }
    @Test void activeDefinitionDraftAndEpochAreIndependent(){
        UUID id=UUID.randomUUID();var v1=lifecycle.legacySave(rule(id,"V1",true),null);
        var pinned=versions.list(true).stream().filter(v->v.ruleId().equals(id)).findFirst().orElseThrow().definition();
        var draft=lifecycle.draft(rule(id,"V2",false),v1.rowRevision());
        assertEquals(v1.activeVersionId(),draft.activeVersionId());assertNotEquals(draft.activeVersionId(),draft.workingVersionId());
        assertEquals(v1.executionEpoch(),draft.executionEpoch());assertEquals("DRAFT",draft.workingState());assertTrue(authorization.current(pinned));
        var pause=lifecycle.transition(id,"pause",draft.rowRevision());assertFalse(authorization.current(pinned));
        var resume=lifecycle.transition(id,"resume",pause.rowRevision());assertEquals("ACTIVE",resume.lifecycle());assertEquals(v1.executionEpoch()+2,resume.executionEpoch());assertFalse(authorization.current(pinned));
        assertEquals("V1",versions.version(id,v1.activeVersionId()).name());
        assertThrows(RuleMutationException.class,()->lifecycle.draft(rule(id,"stale",false),v1.rowRevision()));
    }
    @Test void failedActivationPreservesOwnerAndArchiveIsTerminal(){
        UUID id=UUID.randomUUID();var original=lifecycle.legacySave(rule(id,"valid",true),null);
        var invalid=new Rule(id,"invalid",false,1,List.of(),List.of(),Map.of(),null);
        var draft=lifecycle.draft(invalid,original.rowRevision());
        Rule beforeProjection=projection().get(id);
        // A later actual attempt must remain latest even if its injected clock moves back.
        org.mockito.Mockito.when(clock.instant()).thenReturn(java.time.Instant.now().minusSeconds(3600));
        var failed=lifecycle.transition(id,"activate",draft.rowRevision());
        assertEquals(original.activeVersionId(),failed.activeVersionId());assertEquals(original.executionEpoch(),failed.executionEpoch());assertEquals("ACTIVE",failed.lifecycle());assertFalse(failed.diagnostics().isEmpty());
        assertEquals(draft.rowRevision(),failed.rowRevision());assertEquals(draft.workingVersionId(),failed.workingVersionId());assertSame(beforeProjection,projection().get(id));
        lifecycle.transition(id,"archive",failed.rowRevision());assertTrue(versions.get(id).isEmpty());
        assertThrows(RuleMutationException.class,()->lifecycle.transition(id,"activate",null));
        assertNotNull(versions.version(id,original.activeVersionId()));
    }
    @Test void missingDurableMatchCannotAuthorizeAndManualNeedsNoRuleIdentity(){
        UUID id=UUID.randomUUID();lifecycle.legacySave(rule(id,"source-gate",true),null);
        var pinned=versions.list(true).stream().filter(v->v.ruleId().equals(id)).findFirst().orElseThrow().definition();
        var request=new ActionExecutionRequest(UUID.randomUUID(),UUID.randomUUID(),ActionType.CREATE_ALERT,Map.of());
        assertThrows(RuleExecutionSuppressedException.class,()->new TransactionTemplate(transactions).execute(s->{authorization.check(request,new ActionContext(null,pinned));return null;}));
        authorization.check(new ActionExecutionRequest(UUID.randomUUID(),null,ActionType.SEND_COMMAND,Map.of()),null);
    }
    @Test void modernTokensRequireTruthfulPreconditions(){
        assertThrows(RuleMutationException.class,()->RuleLifecycleController.token(null,null,true));
        assertThrows(RuleMutationException.class,()->RuleLifecycleController.token(1L,"\"2\"",true));
        assertEquals(2L,RuleLifecycleController.token(null,"\"2\"",true));
    }
    private UUID imported(boolean originallyEnabled,boolean invalid){
        UUID id=UUID.randomUUID(),version=UUID.randomUUID();Rule definition=invalid?new Rule(id,"Invalid import",false,1,List.of(),List.of(),Map.of()):rule(id,"Imported",false);
        new TransactionTemplate(transactions).execute(s->{
            sql.update("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata,lifecycle,working_version_id,original_enabled,reconciliation,restoration_eligible) VALUES(?,?,false,1,'[]','[]','{}','PAUSED',?,?,'PENDING',?)",id,definition.name(),version,originallyEnabled,originallyEnabled);
            sql.update("INSERT INTO rule_versions(version_id,rule_id,revision,definition,created_at,provenance) VALUES(?,?,1,?::jsonb,now(),'V24_IMPORT')",version,id,versions.json(definition));
            sql.update("INSERT INTO rule_version_workflow VALUES(?,'DRAFT')",version);
            return null;
        });return id;
    }
    @Test void importsRestoreOnlyValidOriginallyEnabledAndDoNotReactivateOnRetry(){
        UUID enabled=imported(true,false),disabled=imported(false,false),invalid=imported(true,true);
        lifecycle.reconcile(enabled,0);lifecycle.reconcile(disabled,0);lifecycle.reconcile(invalid,0);
        var restored=versions.get(enabled).orElseThrow();assertEquals("ACTIVE",restored.lifecycle());assertEquals("PASSED",restored.reconciliation());
        assertEquals("PAUSED",versions.get(disabled).orElseThrow().lifecycle());assertEquals("FAILED",versions.get(invalid).orElseThrow().reconciliation());
        lifecycle.reconcile(enabled,restored.rowRevision());assertEquals(restored.executionEpoch(),versions.get(enabled).orElseThrow().executionEpoch());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,enabled));
    }
    @Test void staleReconciliationCannotUndoUserPause(){
        UUID id=imported(true,false);long captured=versions.get(id).orElseThrow().rowRevision();
        var paused=lifecycle.transition(id,"pause",captured);
        lifecycle.reconcile(id,captured);assertEquals("PAUSED",versions.get(id).orElseThrow().lifecycle());
        lifecycle.reconcile(id,paused.rowRevision());var result=versions.get(id).orElseThrow();
        assertEquals("PAUSED",result.lifecycle());assertEquals("PASSED",result.reconciliation());assertFalse(result.restorationEligible());
    }
    @Test void overlappingTokenedEditsHaveOneWinner() throws Exception {
        UUID id=UUID.randomUUID();var original=lifecycle.legacySave(rule(id,"original",true),null);
        Rule immutableV1=versions.version(id,original.activeVersionId());
        var a=new Rule(id,"race-A",false,3,List.of(new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"TWIN_UPDATED"),new RuleCondition(RuleField.VALUE,RuleOperator.GT,"35")),
            List.of(new RuleAction("CREATE_ALERT",Map.of("severity","INFO")),new RuleAction("WEBHOOK",Map.of("url","https://example.invalid/a"))),Map.of("owner","race-A","ordering","alert-first"),30);
        var b=new Rule(id,"race-B",false,4,List.of(new RuleCondition(RuleField.VALUE,RuleOperator.GT,"45"),new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"TWIN_UPDATED")),
            List.of(new RuleAction("WEBHOOK",Map.of("url","https://example.invalid/b")),new RuleAction("CREATE_ALERT",Map.of("severity","WARNING"))),Map.of("owner","race-B","ordering","webhook-first"),45);
        record Outcome(Rule submission,RuleVersionStore.View response,Integer conflict){}
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<java.util.concurrent.Future<Outcome>>();
            new TransactionTemplate(transactions).execute(s->{
                sql.queryForList("SELECT rule_id FROM rules WHERE rule_id=? FOR UPDATE",id);
                for(Rule submitted:List.of(a,b))tasks.add(pool.submit(()->{start.await();try{return new Outcome(submitted,lifecycle.draft(submitted,original.rowRevision()),null);}catch(RuleMutationException conflict){return new Outcome(submitted,null,conflict.getStatusCode().value());}}));
                start.countDown();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
                while(System.nanoTime()<deadline){sql.execute("SELECT pg_stat_clear_snapshot()");if(sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT * FROM rules WHERE rule_id=%'",Integer.class)>=2){waiting=true;break;}}
                assertTrue(waiting,"Both tokened edits reached the actual PostgreSQL root lock before release");return null;
            });
            var outcomes=List.of(tasks.get(0).get(15,java.util.concurrent.TimeUnit.SECONDS),tasks.get(1).get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(o->o.response()!=null).count());assertEquals(1,outcomes.stream().filter(o->o.conflict()!=null).count());
            var winner=outcomes.stream().filter(o->o.response()!=null).findFirst().orElseThrow();var loser=outcomes.stream().filter(o->o.conflict()!=null).findFirst().orElseThrow();assertEquals(409,loser.conflict());
            var durable=versions.get(id).orElseThrow();assertEquals(winner.response(),durable);assertEquals(winner.response().workingVersionId(),durable.workingVersionId());
            assertEquals(winner.submission(),versions.version(id,durable.workingVersionId()),"Exact immutable winning content, including ordered conditions/actions and metadata");
            assertTrue(versions.sameDefinition(winner.submission(),durable.definition()));assertFalse(versions.sameDefinition(loser.submission(),durable.definition()));
            assertEquals(List.of(1L,2L),sql.queryForList("SELECT revision FROM rule_versions WHERE rule_id=? ORDER BY revision",Long.class,id));
            assertEquals(2,versions.history(id).size());assertEquals(2L,((Number)versions.history(id).getLast().get("revision")).longValue());assertEquals(durable.workingVersionId(),versions.history(id).getLast().get("versionId"));
            assertEquals(original.rowRevision()+1,durable.rowRevision());assertEquals(original.executionEpoch(),durable.executionEpoch());assertEquals(original.activeVersionId(),durable.activeVersionId());assertEquals("ACTIVE",durable.lifecycle());assertEquals("DRAFT",durable.workingState());assertEquals(immutableV1,versions.version(id,durable.activeVersionId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE rule_versions SET definition='{}' WHERE version_id=?",durable.workingVersionId()));assertEquals(winner.submission(),versions.version(id,durable.workingVersionId()));
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void parallelReconcilersProduceOneDurableAttempt() throws Exception {
        UUID id=imported(true,false);var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var task=(java.util.concurrent.Callable<Void>)()->{start.await();lifecycle.reconcile(id,0);return null;};var a=pool.submit(task);var b=pool.submit(task);start.countDown();a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,id));assertEquals(1,versions.get(id).orElseThrow().executionEpoch());
        }finally{pool.shutdownNow();}
    }
    @Test void failedValidationPersistenceLeavesImportPending(){
        UUID id=imported(true,false);
        sql.execute("CREATE FUNCTION p31_fail_attempt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.rule_id='"+id+"'::uuid THEN RAISE EXCEPTION 'injected infrastructure failure'; END IF; RETURN NEW; END $$");
        sql.execute("CREATE TRIGGER p31_fail_attempt BEFORE INSERT ON rule_validation_attempts FOR EACH ROW EXECUTE FUNCTION p31_fail_attempt()");
        try{assertThrows(RuntimeException.class,()->lifecycle.reconcile(id,0));var pending=versions.get(id).orElseThrow();assertEquals("PENDING",pending.reconciliation());assertEquals("PAUSED",pending.lifecycle());assertNull(pending.activeVersionId());}
        finally{sql.execute("DROP TRIGGER p31_fail_attempt ON rule_validation_attempts");sql.execute("DROP FUNCTION p31_fail_attempt()");}
    }
    @Test void transientContractReadFailureIsNotARecordedInvalidDefinition(){
        UUID id=UUID.randomUUID(),version=UUID.randomUUID();
        Rule definition=new Rule(id,"Transient import",false,1,List.of(new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"0",null,"p31_infra_read",null)),List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of());
        new TransactionTemplate(transactions).execute(s->{
            sql.update("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata,lifecycle,working_version_id,original_enabled,reconciliation,restoration_eligible) VALUES(?,?,false,1,'[]','[]','{}','PAUSED',?,true,'PENDING',true)",id,definition.name(),version);
            sql.update("INSERT INTO rule_versions(version_id,rule_id,revision,definition,created_at,provenance) VALUES(?,?,1,?::jsonb,now(),'V24_IMPORT')",version,id,versions.json(definition));
            sql.update("INSERT INTO rule_version_workflow VALUES(?,'DRAFT')",version);return null;
        });
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Injected contract read outage")).when(definitions).findByKey("p31_infra_read");
        assertThrows(org.springframework.dao.DataAccessException.class,()->lifecycle.reconcile(id,0));
        assertEquals("PENDING",versions.get(id).orElseThrow().reconciliation());assertNull(versions.get(id).orElseThrow().activeVersionId());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?",Integer.class,id));
    }
}
