package com.nexus.rule.persistence;

import com.nexus.rule.application.*;
import com.nexus.rule.domain.*;
import com.nexus.event.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest @Testcontainers @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RuleCooldownRestartIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);r.add("spring.flyway.url",db::getJdbcUrl);r.add("spring.flyway.user",db::getUsername);r.add("spring.flyway.password",db::getPassword);r.add("mqtt.broker.url",()->"tcp://127.0.0.1:1");}
    @MockBean Clock clock;
    @Autowired JdbcTemplate sql;
    @Autowired RuleLifecycleService lifecycle;
    @Autowired RuleVersionStore versions;
    @Autowired RuleMatcher matcher;
    @Autowired RuleMatchPersister matches;
    static Instant now=Instant.parse("2026-10-13T00:00:00Z");
    static UUID id,device,other,active;static long epoch,revision;static Object firstMatcher;
    @BeforeEach void time(){when(clock.instant()).thenAnswer(inv->now);}
    private UUID device(){UUID site=UUID.randomUUID(),building=UUID.randomUUID(),space=UUID.randomUUID(),device=UUID.randomUUID();sql.update("INSERT INTO sites(id,name) VALUES(?,'Cooldown')",site);sql.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'Cooldown')",building,site);sql.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'Cooldown',now(),now())",space,building);sql.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES(?,?,'Cooldown','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')",device,space,device.toString());return device;}
    private Rule definition(String name){return new Rule(id,name,true,1,List.of(new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"TWIN_UPDATED")),List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of(),60);}
    private DomainEvent event(UUID target){return new DomainEvent(UUID.randomUUID(),EventType.TWIN_UPDATED,target,now,"cooldown",Map.of(),"INFO","cooldown");}
    private int count(){return sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?",Integer.class,id);}
    @Test @Order(1) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void persistedLogicalCooldownSurvivesDraftActivationResumeAndLateOldHistory(){
        id=UUID.randomUUID();device=device();other=device();var v1=lifecycle.legacySave(definition("V1"),null);
        Rule pinned=versions.list(true).stream().filter(v->v.ruleId().equals(id)).findFirst().orElseThrow().definition();
        var source=event(device);matcher.onEvent(source);assertEquals(1,count());assertEquals(now,sql.queryForObject("SELECT matched_at FROM rule_matches WHERE rule_id=?",java.sql.Timestamp.class,id).toInstant(),"Live matcher must use injected Clock");
        var draft=lifecycle.draft(definition("V2"),v1.rowRevision());matcher.onEvent(event(other));assertEquals(1,count(),"Cooldown is global for the logical rule, not per device or working version");
        lifecycle.transition(id,"activate",draft.rowRevision());matcher.onEvent(event(device));assertEquals(1,count());lifecycle.transition(id,"pause",null);lifecycle.transition(id,"resume",null);matcher.onEvent(event(other));assertEquals(1,count());
        UUID late=UUID.randomUUID();matches.persist(new RuleMatchedEvent(late,now.plusSeconds(30),new RuleEvaluationResult(true,pinned,source,"Old late V1"),"cooldown"));assertEquals(2,count());assertFalse(sql.queryForObject("SELECT cooldown_eligible FROM rule_matches WHERE match_id=?",Boolean.class,late));
        assertEquals(now,sql.queryForObject("SELECT max(matched_at) FROM rule_matches WHERE rule_id=? AND cooldown_eligible",java.sql.Timestamp.class,id).toInstant());
        var view=versions.get(id).orElseThrow();active=view.activeVersionId();epoch=view.executionEpoch();revision=view.rowRevision();firstMatcher=matcher;
    }
    @Test @Order(2)
    void freshContextHydratesRealEligibleHistoryAndHonorsExactCooldownBoundary(){
        assertNotSame(firstMatcher,matcher);var view=versions.get(id).orElseThrow();assertEquals(active,view.activeVersionId());assertEquals(epoch,view.executionEpoch());assertEquals(revision,view.rowRevision());
        matcher.onEvent(event(other));assertEquals(2,count());now=now.plusSeconds(60);matcher.onEvent(event(device));assertEquals(3,count(),"At exact cooldown boundary a new match is eligible; stale V1 history cannot extend it");
        assertEquals(now,sql.queryForObject("SELECT max(matched_at) FROM rule_matches WHERE rule_id=? AND cooldown_eligible",java.sql.Timestamp.class,id).toInstant());
    }
}
