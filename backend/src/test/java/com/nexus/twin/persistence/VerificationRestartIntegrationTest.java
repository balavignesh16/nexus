package com.nexus.twin.persistence;

import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.binding.*;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.*;
import com.nexus.device.connectivity.*;
import com.nexus.device.domain.*;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.twin.application.*;
import com.nexus.twin.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerificationRestartIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    static VerificationStateId noEvidenceId;
    static VerificationStateId mismatchId;
    static UUID noEvidenceCommand;
    static UUID mismatchCommand;
    static Instant noEvidenceDeadline;
    static Instant mismatchDeadline;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("spring.flyway.url",postgres::getJdbcUrl);
        registry.add("spring.flyway.user",postgres::getUsername);
        registry.add("spring.flyway.password",postgres::getPassword);
        registry.add("nexus.twin.verification-scan-ms",()->"3600000");
    }
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired CapabilityDefinitionRepository definitions;
    @Autowired DeviceCapabilityBindingRepository bindings;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired ReadbackMappingService mappings;
    @Autowired CommandAcceptanceService acceptance;
    @Autowired TelemetryService telemetry;
    @Autowired CurrentTwinVerificationRepository verification;
    @Autowired DesiredTwinStateRepository desired;
    @Autowired TwinStateTransitionRepository transitions;
    @Autowired VerificationDeadlineWorker worker;
    @MockBean CommandExecutor executor;

    @Test @Order(1) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void persistTwoPendingVerificationsBeforeSpringContextRestart() {
        var noEvidence=create(true);
        var mismatch=create(false);
        noEvidenceId=noEvidence.id(); mismatchId=mismatch.id();
        noEvidenceCommand=noEvidence.command(); mismatchCommand=mismatch.command();
        noEvidenceDeadline=verification.findById(noEvidenceId).orElseThrow().getVerificationDeadlineAt();
        mismatchDeadline=verification.findById(mismatchId).orElseThrow().getVerificationDeadlineAt();
        assertTrue(noEvidenceDeadline.isAfter(Instant.now()));
        assertTrue(mismatchDeadline.isAfter(Instant.now()));
        assertEquals(VerificationStatus.PENDING,verification.findById(noEvidenceId).orElseThrow().getVerificationStatus());
        assertEquals(VerificationStatus.PENDING,verification.findById(mismatchId).orElseThrow().getVerificationStatus());
        assertEquals("MISMATCH",verification.findById(mismatchId).orElseThrow().getComparisonResult());
    }

    @Test @Order(2) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void freshSpringContextReadsSamePendingOwnershipAndDeadlineThenStopsBeforeExpiry() {
        assertNotNull(noEvidenceId);
        assertEquals(noEvidenceCommand,desired.findById(new DesiredTwinStateId(noEvidenceId.deviceId(),noEvidenceId.targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(mismatchCommand,desired.findById(new DesiredTwinStateId(mismatchId.deviceId(),mismatchId.targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(noEvidenceCommand,verification.findById(noEvidenceId).orElseThrow().getDesiredCommandId());
        assertEquals(mismatchCommand,verification.findById(mismatchId).orElseThrow().getDesiredCommandId());
        assertEquals(noEvidenceDeadline,verification.findById(noEvidenceId).orElseThrow().getVerificationDeadlineAt());
        assertEquals(mismatchDeadline,verification.findById(mismatchId).orElseThrow().getVerificationDeadlineAt());
        assertEquals(VerificationStatus.PENDING,verification.findById(noEvidenceId).orElseThrow().getVerificationStatus());
        assertEquals(VerificationStatus.PENDING,verification.findById(mismatchId).orElseThrow().getVerificationStatus());
        assertEquals(0,terminalCount(noEvidenceId.deviceId()));
        assertEquals(0,terminalCount(mismatchId.deviceId()));
        // Deterministic clock advancement through persisted deadlines; no wall-clock sleep.
        setExpired(noEvidenceId);setExpired(mismatchId);
    }

    @Test @Order(3)
    void nextSpringContextReconcilesOverdueRowsExactlyOnceAgainstSameDatabase() {
        worker.reconcile();
        assertEquals(VerificationStatus.TIMED_OUT,verification.findById(noEvidenceId).orElseThrow().getVerificationStatus());
        assertEquals("NO_QUALIFIED_READBACK",verification.findById(noEvidenceId).orElseThrow().getDiscrepancyReason());
        assertNull(verification.findById(noEvidenceId).orElseThrow().getLastReadbackTelemetryId());
        assertEquals(VerificationStatus.DIVERGED,verification.findById(mismatchId).orElseThrow().getVerificationStatus());
        assertEquals("QUALIFIED_READBACK_MISMATCH",verification.findById(mismatchId).orElseThrow().getDiscrepancyReason());
        assertNotNull(verification.findById(mismatchId).orElseThrow().getLastReadbackTelemetryId());
        assertEquals(noEvidenceCommand,desired.findById(new DesiredTwinStateId(noEvidenceId.deviceId(),noEvidenceId.targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(mismatchCommand,desired.findById(new DesiredTwinStateId(mismatchId.deviceId(),mismatchId.targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(1,terminalCount(noEvidenceId.deviceId()));
        assertEquals(1,terminalCount(mismatchId.deviceId()));
        worker.reconcile();
        assertEquals(1,terminalCount(noEvidenceId.deviceId()));
        assertEquals(1,terminalCount(mismatchId.deviceId()));
    }

    private record Fixture(VerificationStateId id,UUID command) {}
    private Fixture create(boolean withoutEvidence) {
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Verification restart","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var equipment=devices.save(new Device(UUID.randomUUID(),space,"LED",DeviceType.CUSTOM,
                "NEXUS","TEST","P24-RESTART-"+UUID.randomUUID(),null,DeviceMode.SIMULATED));
        equipment.update("LED",DeviceStatus.ACTIVE,null);
        UUID device=devices.save(equipment).getId();
        var write=definitions.findByKey("led").orElseThrow();
        var read=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"restart_read_"+UUID.randomUUID().toString().replace("-", ""),
                "LED readback",null,CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",
                List.of("BOOLEAN"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var writeBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,write,null,true,"BOOLEAN",
                Map.of(),Map.of("commandCapability","LED"),now));
        bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,read,null,true,"BOOLEAN",
                Map.of(),Map.of("telemetryKey","RESTART_READ"),now));
        var readBinding=bindings.findByDeviceId(device).stream().filter(b->b.getDefinition().getId().equals(read.getId())).findFirst().orElseThrow();
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",3600));
        UUID command=UUID.randomUUID();
        var request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",device.toString(),"capabilityKey","led","command","SET_STATE","value",true));
        when(executor.prepare(eq(request))).thenReturn(new CommandExecutor.PreparedCommand(device,writeBinding.getId(),null,
                write.getId(),"led",CapabilityValueType.BOOLEAN,"BOOLEAN","","LED","SET_STATE",true));
        acceptance.accept(request,new ActionContext(null,null),now);
        if(!withoutEvidence)telemetry.processTelemetry(new TelemetryRequest(device,Instant.now(),"RESTART_READ",false,
                "BOOLEAN",read.getKey(),command));
        return new Fixture(new VerificationStateId(device,"binding:"+writeBinding.getId(),""),command);
    }
    private void setExpired(VerificationStateId id) {
        var row=verification.findById(id).orElseThrow();
        ReflectionTestUtils.setField(row,"verificationDeadlineAt",Instant.now().minusSeconds(1));
        verification.saveAndFlush(row);
    }
    private long terminalCount(UUID device){
        return transitions.findAll().stream().filter(t->device.equals(t.getDeviceId())
                &&t.getTransitionType()==TwinTransitionType.CONVERGENCE_CHANGED
                &&Set.of("TIMED_OUT","DIVERGED").contains(t.getNewVerificationStatus())).count();
    }
}
