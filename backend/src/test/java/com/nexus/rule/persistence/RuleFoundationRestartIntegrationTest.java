package com.nexus.rule.persistence;

import com.nexus.rule.application.RuleLifecycleService;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RuleFoundationRestartIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
        r.add("spring.flyway.url",db::getJdbcUrl);r.add("spring.flyway.user",db::getUsername);r.add("spring.flyway.password",db::getPassword);
    }
    @Autowired RuleLifecycleService lifecycle;
    @Autowired RuleVersionStore versions;
    @Autowired RuleRegistry registry;
    static UUID id,active,working;
    static long revision,epoch;
    static Object firstContextRegistry;
    private Rule definition(String name){return new Rule(id,name,true,1,List.of(new RuleCondition(RuleField.EVENT_TYPE,RuleOperator.EQ,"TWIN_UPDATED")),List.of(new RuleAction("CREATE_ALERT",Map.of())),Map.of(),30);}
    @Test @Order(1) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void persistActiveVersionAndSuccessorBeforeContextClose(){
        id=UUID.randomUUID();var v1=lifecycle.legacySave(definition("V1"),null);
        var v2=lifecycle.draft(definition("V2 draft"),v1.rowRevision());
        active=v2.activeVersionId();working=v2.workingVersionId();revision=v2.rowRevision();epoch=v2.executionEpoch();firstContextRegistry=registry;
        assertNotEquals(active,working);
    }
    @Test @Order(2)
    void freshContextReloadsSameDatabaseOwnershipWithoutActivation(){
        assertNotSame(firstContextRegistry,registry,"Actual Spring application context recreation, not an OS-process restart");
        var view=versions.get(id).orElseThrow();assertEquals(active,view.activeVersionId());assertEquals(working,view.workingVersionId());assertEquals(revision,view.rowRevision());assertEquals(epoch,view.executionEpoch());assertEquals("ACTIVE",view.lifecycle());assertEquals("DRAFT",view.workingState());
        var executable=registry.listEnabledOrderedByPriority().stream().filter(r->r.ruleId().equals(id)).findFirst().orElseThrow();assertEquals("V1",executable.name());assertEquals(active,executable.executionIdentity().ruleVersionId());assertEquals(epoch,executable.executionIdentity().executionEpoch());
        assertEquals(2,versions.history(id).size());
    }
}
