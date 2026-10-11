package com.nexus.twin.application;

import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.CommandDispatchCoordinator;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.binding.BindingManagementService;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.*;
import com.nexus.device.connectivity.*;
import com.nexus.device.domain.*;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.template.*;
import com.nexus.device.onboarding.DeviceProvisioningService;
import com.nexus.device.onboarding.api.ProvisionDeviceRequest;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.twin.domain.*;
import com.nexus.twin.api.DigitalTwinController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest @Testcontainers
class VerificationIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);r.add("spring.flyway.url",postgres::getJdbcUrl);
        r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);
        r.add("nexus.twin.verification-scan-ms",()->"3600000");
    }
    @Autowired SiteRepository sites; @Autowired BuildingRepository buildings; @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices; @Autowired DeviceCapabilityBindingRepository bindings;
    @Autowired CapabilityDefinitionRepository definitions; @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired TelemetryService telemetry; @Autowired CommandAcceptanceService acceptance;
    @Autowired ReadbackMappingService mappings; @Autowired CurrentTwinVerificationRepository verification;
    @Autowired TwinStateTransitionRepository transitions;
    @Autowired DigitalTwinRepository reported;
    @Autowired VerificationService reconciliation;
    @Autowired DesiredTwinStateRepository desired;
    @Autowired ActionHistoryRepository actions;
    @Autowired DeviceCommandAckMessageHandler ackHandler;
    @Autowired CommandDispatchCoordinator dispatchCoordinator;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TwinStateQualityEvaluator qualityEvaluator;
    @Autowired ReadbackComparison readbackComparison;
    @Autowired DataSource dataSource;
    @Autowired DeviceTemplateRepository templates;
    @Autowired DeviceTemplateCapabilityRepository templateCapabilities;
    @Autowired DeviceProvisioningService provisioning;
    @MockBean CommandExecutor executor;

    @Test void oldEqualValueAndAckDoNotConvergeOnlyNewMappedEchoDoes() throws Exception {
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Verify","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var equipment=devices.save(new Device(UUID.randomUUID(),space,"Actuator",DeviceType.CUSTOM,
                "NEXUS","TEST","P24-"+UUID.randomUUID(),null,DeviceMode.SIMULATED));
        equipment.update("Actuator",DeviceStatus.ACTIVE,null);
        UUID device=devices.save(equipment).getId();
        var write=definitions.findByKey("led").orElseThrow();
        var read=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"led_readback_"+UUID.randomUUID().toString().replace("-", ""),
                "LED readback",null,CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",
                List.of("BOOLEAN"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var writeBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,write,null,true,"BOOLEAN",
                Map.of(),Map.of("commandCapability","LED"),now));
        var readBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,read,null,true,"BOOLEAN",
                Map.of(),Map.of("telemetryKey","LED_READBACK"),now));
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        Instant oldEvent=now.minusSeconds(10);
        telemetry.processTelemetry(new TelemetryRequest(device,oldEvent,"LED_READBACK",true,"BOOLEAN",read.getKey()));
        UUID first=accept(device,writeBinding,write,now);
        var id=new VerificationStateId(device,"binding:"+writeBinding.getId(),"");
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(first,desired.findById(new DesiredTwinStateId(device,id.targetKey(),id.subtargetKey())).orElseThrow().getCommandId());
        assertEquals(1,reported.findByDeviceId(device).size());
        assertEquals(1,transitionsFor(device,TwinTransitionType.REPORTED_STATE_CHANGED).size());
        Instant firstPublished=Instant.now();
        assertEquals(1,actions.recordSuccessfulPublication(first,firstPublished,firstPublished.plusSeconds(30),"published"));
        String ackTopic="nexus/devices/"+device+"/commands/ack";
        ackHandler.handle(ackTopic,("{\"commandId\":\""+first+"\",\"status\":\"ACCEPTED\",\"timestamp\":"
                +Instant.now().getEpochSecond()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(ExecutionStatus.SUCCESS,actions.findById(first).orElseThrow().getStatus());
        assertEquals(AcknowledgementStatus.ACCEPTED,actions.findById(first).orElseThrow().getAckStatus());
        assertEquals(CommandStatus.ACKNOWLEDGED,CommandStatus.from(actions.findById(first).orElseThrow(),java.time.Clock.systemUTC()));
        assertEquals(0,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())).count());
        assertThrows(IllegalArgumentException.class,()->mappings.addDevice(device,
                new ReadbackMappingService.Policy(writeBinding.getId(),"",writeBinding.getId(),
                        "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",10)));
        assertThrows(IllegalArgumentException.class,()->mappings.addDevice(device,
                new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                        "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",0)));
        mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",10));
        assertThrows(IllegalArgumentException.class,()->mappings.addDevice(device,
                new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                        "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",10)));
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(id).orElseThrow().getVerificationStatus());
        UUID second=accept(device,writeBinding,write,Instant.now());
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(second,desired.findById(new DesiredTwinStateId(device,id.targetKey(),id.subtargetKey())).orElseThrow().getCommandId());
        Instant publishedAt=Instant.now();
        assertEquals(1,actions.recordSuccessfulPublication(second,publishedAt,publishedAt.plusSeconds(30),"published"));
        assertEquals(CommandStatus.PUBLISHED,CommandStatus.from(actions.findById(second).orElseThrow(),
                java.time.Clock.systemUTC()));
        long ackEpoch=Instant.now().getEpochSecond();
        String acceptedAck="{\"commandId\":\""+second+"\",\"status\":\"ACCEPTED\",\"timestamp\":"+ackEpoch+"}";
        ackHandler.handle(ackTopic,acceptedAck.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant backendAckAt=actions.findById(second).orElseThrow().getAckBackendReceivedAt();
        assertNotNull(backendAckAt);
        ackHandler.handle(ackTopic,acceptedAck.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String conflictingAck="{\"commandId\":\""+second+"\",\"status\":\"REJECTED\",\"timestamp\":"+ackEpoch+"}";
        ackHandler.handle(ackTopic,conflictingAck.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(AcknowledgementStatus.ACCEPTED,actions.findById(second).orElseThrow().getAckStatus());
        assertEquals(backendAckAt,actions.findById(second).orElseThrow().getAckBackendReceivedAt());
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        ackHandler.handle(ackTopic,acceptedAck.replace(second.toString(),first.toString())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(second,verification.findById(id).orElseThrow().getDesiredCommandId());
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,now.minusSeconds(6),"LED_READBACK",true,"BOOLEAN",read.getKey()));
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        assertNull(verification.findById(id).orElseThrow().getLastReadbackTelemetryId());
        assertEquals(0,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())).count());
        telemetry.processTelemetry(new TelemetryRequest(device,now.minusSeconds(5),"LED_READBACK",true,"BOOLEAN",read.getKey(),first));
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(second,verification.findById(id).orElseThrow().getDesiredCommandId());
        assertNull(verification.findById(id).orElseThrow().getLastReadbackTelemetryId());
        telemetry.processTelemetry(new TelemetryRequest(device,now.minusSeconds(2),"LED_READBACK",false,"BOOLEAN",read.getKey(),second));
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals("MISMATCH",verification.findById(id).orElseThrow().getComparisonResult());
        assertEquals("QUALIFIED_READBACK_MISMATCH",verification.findById(id).orElseThrow().getDiscrepancyReason());
        telemetry.processTelemetry(new TelemetryRequest(device,now.minusSeconds(1),"LED_READBACK",true,"BOOLEAN",read.getKey(),second));
        var state=verification.findById(id).orElseThrow();
        assertEquals(VerificationStatus.CONVERGED,state.getVerificationStatus());
        assertEquals(CommandStatus.ACKNOWLEDGED,CommandStatus.from(actions.findById(second).orElseThrow(),
                java.time.Clock.systemUTC()));
        assertEquals(second,state.getDesiredCommandId());
        assertNotNull(state.getLastReadbackTelemetryId());
        assertEquals("COMMAND_ID_MATCH",state.getReadbackAttribution());
        assertEquals("MATCH",state.getComparisonResult());
        assertEquals(1,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())&&second.equals(t.getVerificationCommandId())).count());
        assertTrue(transitions.findAll().stream().anyMatch(t->t.getTransitionType()==TwinTransitionType.CONVERGENCE_CHANGED
                && "CONVERGED".equals(t.getNewVerificationStatus())&&second.equals(t.getVerificationCommandId())));
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(1),"LED_READBACK",false,"BOOLEAN",read.getKey(),second));
        assertEquals(VerificationStatus.DIVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals("QUALIFIED_READBACK_MISMATCH",verification.findById(id).orElseThrow().getDiscrepancyReason());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(2),"LED_READBACK",true,"BOOLEAN",read.getKey(),second));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(2,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())&&second.equals(t.getVerificationCommandId())).count());
        mappings.disableDevice(device,mappingsDeviceId(device,writeBinding.getId()));
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(id).orElseThrow().getVerificationStatus());
        mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"EXACT",null,"NONE",10));
        accept(device,writeBinding,write,Instant.now());
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(id).orElseThrow().getVerificationStatus());
        mappings.disableDevice(device,mappingsDeviceId(device,writeBinding.getId()));
        mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",10));
        UUID third=accept(device,writeBinding,write,Instant.now());
        var pending=verification.findById(id).orElseThrow();
        assertEquals(VerificationStatus.PENDING,pending.getVerificationStatus());
        org.springframework.test.util.ReflectionTestUtils.setField(pending,"verificationDeadlineAt",Instant.now().minusSeconds(1));
        verification.saveAndFlush(pending);
        var processors=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var firstProcessor=processors.submit(()->reconciliation.deadline(id));
            var secondProcessor=processors.submit(()->reconciliation.deadline(id));
            firstProcessor.get(15,java.util.concurrent.TimeUnit.SECONDS);
            secondProcessor.get(15,java.util.concurrent.TimeUnit.SECONDS);
        } finally { processors.shutdownNow(); }
        assertEquals(VerificationStatus.TIMED_OUT,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals("NO_QUALIFIED_READBACK",verification.findById(id).orElseThrow().getDiscrepancyReason());
        assertEquals(1,transitions.findAll().stream().filter(t->t.getTransitionType()==TwinTransitionType.CONVERGENCE_CHANGED
                && "TIMED_OUT".equals(t.getNewVerificationStatus())&&third.equals(t.getVerificationCommandId())).count());
        reconciliation.deadline(id);
        assertEquals(VerificationStatus.TIMED_OUT,verification.findById(id).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(3),"LED_READBACK",false,"BOOLEAN",read.getKey(),third));
        assertEquals(VerificationStatus.DIVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(4),"LED_READBACK",true,"BOOLEAN",read.getKey(),third));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(1,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"DIVERGED".equals(t.getNewVerificationStatus())&&third.equals(t.getVerificationCommandId())).count());
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.OFFLINE);
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(5),"LED_READBACK",false,"BOOLEAN",read.getKey(),third));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        reconciliation.clearDesired(device,id.targetKey(),id.subtargetKey(),"test-clear");
        assertTrue(verification.findById(id).isEmpty());
        assertTrue(desired.findById(new DesiredTwinStateId(device,id.targetKey(),id.subtargetKey())).isEmpty());
        assertTrue(twinController.getTwin(device).getBody().desiredState().isEmpty());
        assertEquals("NO_DESIRED_STATE",twinController.getTwin(device).getBody().verification()
                .get(id.targetKey()).status());
        assertTrue(transitions.findAll().stream().anyMatch(t->t.getTransitionType()==TwinTransitionType.DESIRED_STATE_CLEARED));
        assertEquals(1,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"NO_DESIRED_STATE".equals(t.getNewVerificationStatus())).count());
        reconciliation.clearDesired(device,id.targetKey(),id.subtargetKey(),"duplicate-clear");
        assertEquals(1,transitionsFor(device,TwinTransitionType.DESIRED_STATE_CLEARED).size());
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        UUID noAck=accept(device,writeBinding,write,Instant.now());
        Instant noAckPublished=Instant.now();
        assertEquals(1,actions.recordSuccessfulPublication(noAck,noAckPublished,noAckPublished.plusSeconds(30),"published"));
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.OFFLINE);
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(6),"LED_READBACK",true,"BOOLEAN",read.getKey(),noAck));
        assertEquals(VerificationStatus.PENDING,verification.findById(id).orElseThrow().getVerificationStatus());
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(7),"LED_READBACK",true,"BOOLEAN",read.getKey(),noAck));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(CommandStatus.PUBLISHED,CommandStatus.from(actions.findById(noAck).orElseThrow(),
                java.time.Clock.systemUTC()));
        UUID rejected=accept(device,writeBinding,write,Instant.now());
        Instant rejectedPublished=Instant.now();
        assertEquals(1,actions.recordSuccessfulPublication(rejected,rejectedPublished,rejectedPublished.plusSeconds(30),"published"));
        ackHandler.handle(ackTopic,("{\"commandId\":\""+rejected+"\",\"status\":\"REJECTED\",\"timestamp\":"
                +Instant.now().getEpochSecond()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(CommandStatus.REJECTED,CommandStatus.from(actions.findById(rejected).orElseThrow(),
                java.time.Clock.systemUTC()));
    }

    @Test void templateMappingMaterializesAgainstBindingsAndReferencedTemplateIsImmutable() {
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Template site","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var template=templates.save(new DeviceTemplate(UUID.randomUUID(),"p24_"+UUID.randomUUID().toString().replace("-", ""),
                "P2.4 test",null,null,null,ProtocolType.MQTT,true,Map.of()));
        var write=definitions.findByKey("led").orElseThrow();
        var read=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"led_template_readback_"
                +UUID.randomUUID().toString().replace("-", ""),"LED readback",null,
                CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",List.of("BOOLEAN"),
                null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var writeMember=templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(),template,write,true,
                "BOOLEAN",0,null,Map.of()));
        var readMember=templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(),template,read,true,
                "BOOLEAN",1,null,Map.of()));
        var policy=new ReadbackMappingService.Policy(writeMember.getId(),"",readMember.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",10);
        var templateMapping=mappings.addTemplate(template.getId(),policy);
        var result=provisioning.provision(new ProvisionDeviceRequest("p24-"+UUID.randomUUID(),template.getId(),
                space.getId(),"Mapped actuator","P24-MAPPED-"+UUID.randomUUID(),DeviceMode.SIMULATED,Map.of()));
        var runtime=runtimeMappings.findByDeviceId(result.deviceId());
        assertEquals(1,runtime.size());
        assertEquals(templateMapping.getId(),runtime.getFirst().getTemplateMappingId());
        assertNotEquals(writeMember.getId(),runtime.getFirst().getWriteBindingId());
        assertNotEquals(readMember.getId(),runtime.getFirst().getReadBindingId());
        assertEquals(result.deviceId(),bindings.findById(runtime.getFirst().getWriteBindingId()).orElseThrow().getDeviceId());
        assertEquals(result.deviceId(),bindings.findById(runtime.getFirst().getReadBindingId()).orElseThrow().getDeviceId());
        assertThrows(IllegalStateException.class,()->mappings.addTemplate(template.getId(),policy));
    }

    @Test void relayChannelsOwnIndependentVerificationAndNeverUseTheOtherChannelsEcho() {
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Relay verify","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var equipment=devices.save(new Device(UUID.randomUUID(),space,"Relay",DeviceType.CUSTOM,
                "NEXUS","TEST","P24-RELAY-"+UUID.randomUUID(),null,DeviceMode.SIMULATED));
        equipment.update("Relay",DeviceStatus.ACTIVE,null);
        UUID device=devices.save(equipment).getId();
        var write=definitions.findByKey("relay").orElseThrow();
        var readOne=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"relay_one_"+UUID.randomUUID().toString().replace("-", ""),
                "Relay one readback",null,CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",
                List.of("BOOLEAN"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var readTwo=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"relay_two_"+UUID.randomUUID().toString().replace("-", ""),
                "Relay two readback",null,CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",
                List.of("BOOLEAN"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var writeBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,write,null,true,"BOOLEAN",
                Map.of("channels",List.of(1,2)),Map.of("commandCapability","RELAY"),now));
        var oneBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,readOne,null,true,"BOOLEAN",
                Map.of(),Map.of("telemetryKey","RELAY_ONE"),now));
        var twoBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,readTwo,null,true,"BOOLEAN",
                Map.of(),Map.of("telemetryKey","RELAY_TWO"),now));
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        var oneId=new VerificationStateId(device,"binding:"+writeBinding.getId(),"channel:1");
        var twoId=new VerificationStateId(device,"binding:"+writeBinding.getId(),"channel:2");
        UUID writeOnly=acceptRelay(device,writeBinding,write,1,true);
        Instant published=Instant.now();
        assertEquals(1,actions.recordSuccessfulPublication(writeOnly,published,published.plusSeconds(30),"published"));
        ackHandler.handle("nexus/devices/"+device+"/commands/ack",("{\"commandId\":\""+writeOnly
                +"\",\"status\":\"ACCEPTED\",\"timestamp\":"+Instant.now().getEpochSecond()+"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(oneId).orElseThrow().getVerificationStatus());
        assertTrue(verification.findById(twoId).isEmpty());
        assertEquals(CommandStatus.ACKNOWLEDGED,CommandStatus.from(actions.findById(writeOnly).orElseThrow(),java.time.Clock.systemUTC()));
        assertEquals(0,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())).count());
        var oneMapping=mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"channel:1",oneBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",30));
        var twoMapping=mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"channel:2",twoBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",30));
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(oneId).orElseThrow().getVerificationStatus());
        UUID one=acceptRelay(device,writeBinding,write,1,true);
        UUID two=acceptRelay(device,writeBinding,write,2,true);
        assertEquals(one,verification.findById(oneId).orElseThrow().getDesiredCommandId());
        assertEquals(two,verification.findById(twoId).orElseThrow().getDesiredCommandId());
        assertEquals(oneMapping.getId(),verification.findById(oneId).orElseThrow().getMappingId());
        assertEquals(twoMapping.getId(),verification.findById(twoId).orElseThrow().getMappingId());
        assertEquals(VerificationStatus.PENDING,verification.findById(oneId).orElseThrow().getVerificationStatus());
        assertEquals(VerificationStatus.PENDING,verification.findById(twoId).orElseThrow().getVerificationStatus());
        assertEquals(2,desired.findByDeviceId(device).size());
        telemetry.processTelemetry(new TelemetryRequest(device,Instant.now(),"RELAY_ONE",true,"BOOLEAN",readOne.getKey(),one));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(oneId).orElseThrow().getVerificationStatus());
        assertEquals(VerificationStatus.PENDING,verification.findById(twoId).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,Instant.now().plusSeconds(1),"RELAY_TWO",false,"BOOLEAN",readTwo.getKey(),two));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(oneId).orElseThrow().getVerificationStatus());
        assertEquals(VerificationStatus.PENDING,verification.findById(twoId).orElseThrow().getVerificationStatus());
        assertEquals("MISMATCH",verification.findById(twoId).orElseThrow().getComparisonResult());
        telemetry.processTelemetry(new TelemetryRequest(device,Instant.now().plusSeconds(2),"RELAY_TWO",true,"BOOLEAN",readTwo.getKey(),two));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(twoId).orElseThrow().getVerificationStatus());
        assertEquals(one,desired.findById(new DesiredTwinStateId(device,oneId.targetKey(),"channel:1")).orElseThrow().getCommandId());
        assertEquals(two,desired.findById(new DesiredTwinStateId(device,twoId.targetKey(),"channel:2")).orElseThrow().getCommandId());
        var convergence=transitions.findAll().stream().filter(t->t.getDeviceId().equals(device)
                &&t.getTransitionType()==TwinTransitionType.CONVERGENCE_CHANGED).toList();
        assertEquals(1,convergence.stream().filter(t->t.getSubtargetKey().equals("channel:1")
                &&"CONVERGED".equals(t.getNewVerificationStatus())&&one.equals(t.getVerificationCommandId())).count());
        assertEquals(1,convergence.stream().filter(t->t.getSubtargetKey().equals("channel:2")
                &&"CONVERGED".equals(t.getNewVerificationStatus())&&two.equals(t.getVerificationCommandId())).count());
    }

    @Test void telemetryRacingNewDesiredCannotMakeSupersededEchoConvergeNewOwner() throws Exception {
        var fixture=booleanFixture();
        UUID old=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        UUID[] newer=new UUID[1];
        race(()->echo(fixture,true,old),()->newer[0]=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now()));
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(newer[0],state.getDesiredCommandId());
        assertEquals(newer[0],desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(VerificationStatus.PENDING,state.getVerificationStatus());
        assertEquals(1,verification.findByDeviceId(fixture.device()).size());
        assertEquals(0,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->newer[0].equals(t.getVerificationCommandId())&&"CONVERGED".equals(t.getNewVerificationStatus())).count());
    }

    @Test void telemetryLockFirstConvergesOldOwnerThenNewAcceptanceRearmsPending() throws Exception {
        var fixture=booleanFixture();
        UUID old=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        var telemetryLocked=new java.util.concurrent.CountDownLatch(1);
        var releaseTelemetry=new java.util.concurrent.CountDownLatch(1);
        var replacementStarted=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var observed=pool.submit(()->{
                new TransactionTemplate(transactionManager).executeWithoutResult(ignored->{
                    devices.findByIdForUpdate(fixture.device()).orElseThrow();
                    echo(fixture,true,old);
                    telemetryLocked.countDown();
                    await(releaseTelemetry);
                });
                return null;
            });
            assertTrue(telemetryLocked.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var replacement=pool.submit(()->{
                replacementStarted.countDown();
                return accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
            });
            assertTrue(replacementStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));
            awaitDeviceLockWait();
            releaseTelemetry.countDown();
            observed.get(20,java.util.concurrent.TimeUnit.SECONDS);
            UUID newer=replacement.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertRaceFinalOwner(fixture,newer);
            var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
            assertEquals(1,history.stream().filter(t->old.equals(t.getVerificationCommandId())
                    &&"CONVERGED".equals(t.getNewVerificationStatus())).count());
            assertEquals(1,history.stream().filter(t->newer.equals(t.getVerificationCommandId())
                    &&"PENDING".equals(t.getNewVerificationStatus())).count());
            var oldConvergence=history.stream().filter(t->old.equals(t.getVerificationCommandId())
                    &&"CONVERGED".equals(t.getNewVerificationStatus())).findFirst().orElseThrow();
            var newPending=history.stream().filter(t->newer.equals(t.getVerificationCommandId())
                    &&"PENDING".equals(t.getNewVerificationStatus())).findFirst().orElseThrow();
            assertFalse(oldConvergence.getTransitionedAt().isAfter(newPending.getTransitionedAt()));
        } finally { releaseTelemetry.countDown();pool.shutdownNow(); }
    }

    @Test void desiredLockFirstMakesOldEchoReportedOnlyWithoutConvergingReplacement() throws Exception {
        var fixture=booleanFixture();
        UUID old=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        UUID newer=UUID.randomUUID();
        var request=new ActionExecutionRequest(newer,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",fixture.device().toString(),"capabilityKey","led","command","SET_STATE","value",true));
        var prepared=new CommandExecutor.PreparedCommand(fixture.device(),fixture.writeBinding().getId(),null,
                fixture.write().getId(),"led",CapabilityValueType.BOOLEAN,"BOOLEAN","","LED","SET_STATE",true);
        var desiredLocked=new java.util.concurrent.CountDownLatch(1);
        var releaseDesired=new java.util.concurrent.CountDownLatch(1);
        when(executor.prepare(eq(request))).thenAnswer(ignored->{
            desiredLocked.countDown();
            await(releaseDesired);
            return prepared;
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var replacement=pool.submit(()->{
                acceptance.accept(request,new ActionContext(null,null),Instant.now());
                return null;
            });
            assertTrue(desiredLocked.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var observed=pool.submit(()->{echo(fixture,true,old);return null;});
            awaitDeviceLockWait();
            releaseDesired.countDown();
            replacement.get(20,java.util.concurrent.TimeUnit.SECONDS);
            observed.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertRaceFinalOwner(fixture,newer);
            assertEquals(true,reported.findByDeviceIdAndStateKey(fixture.device(),
                    "binding:"+fixture.readBinding().getId()).orElseThrow().typedValue());
            assertEquals(0,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                    .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())).count());
        } finally { releaseDesired.countDown();pool.shutdownNow(); }
    }

    @Test void timeoutRacingNewDesiredNeverMutatesReplacementOwner() throws Exception {
        var fixture=booleanFixture();
        UUID old=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        expire(fixture.id());
        UUID[] newer=new UUID[1];
        race(()->reconciliation.deadline(fixture.id()),
                ()->newer[0]=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now()));
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(newer[0],state.getDesiredCommandId());
        assertEquals(VerificationStatus.PENDING,state.getVerificationStatus());
        assertEquals(1,verification.findByDeviceId(fixture.device()).size());
        assertTrue(transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->old.equals(t.getVerificationCommandId())&&"TIMED_OUT".equals(t.getNewVerificationStatus())).count()<=1);
        assertEquals(0,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->newer[0].equals(t.getVerificationCommandId())&&"TIMED_OUT".equals(t.getNewVerificationStatus())).count());
    }

    @Test void timeoutRacingMatchingTelemetryHasOneCoherentFinalConvergence() throws Exception {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        expire(fixture.id());
        race(()->reconciliation.deadline(fixture.id()),()->echo(fixture,true,command));
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(VerificationStatus.CONVERGED,state.getVerificationStatus());
        assertEquals("MATCH",state.getComparisonResult());
        assertTrue(transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->command.equals(t.getVerificationCommandId())&&"TIMED_OUT".equals(t.getNewVerificationStatus())).count()<=1);
        assertEquals(1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->command.equals(t.getVerificationCommandId())&&"CONVERGED".equals(t.getNewVerificationStatus())).count());
    }

    @Test void mappingDisableRacingTelemetryUltimatelyLeavesCurrentIntentUnverified() throws Exception {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        race(()->mappings.disableDevice(fixture.device(),fixture.mapping().getId()),()->echo(fixture,true,command));
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(VerificationStatus.UNVERIFIED,state.getVerificationStatus());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(1,verification.findByDeviceId(fixture.device()).size());
    }

    @Test void mappingDisableRacingNewDesiredNeverArmsAgainstDisabledMapping() throws Exception {
        var fixture=booleanFixture();
        accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        UUID[] newer=new UUID[1];
        race(()->mappings.disableDevice(fixture.device(),fixture.mapping().getId()),
                ()->newer[0]=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now()));
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(newer[0],state.getDesiredCommandId());
        assertEquals(VerificationStatus.UNVERIFIED,state.getVerificationStatus());
        assertEquals(newer[0],desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(1,verification.findByDeviceId(fixture.device()).size());
    }

    @Test void duplicateAndOutOfOrderEchoCannotRewriteReportedOrConvergenceHistory() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        Instant firstEvent=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusNanos(321);
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),firstEvent,"RACE_READ",true,
                "BOOLEAN",fixture.read().getKey(),command));
        var firstState=verification.findById(fixture.id()).orElseThrow();
        UUID evidence=firstState.getLastReadbackTelemetryId();
        var firstReported=reported.findByDeviceIdAndStateKey(fixture.device(),
                "binding:"+fixture.readBinding().getId()).orElseThrow();
        Instant firstReceivedAt=firstReported.receivedAt();
        assertEquals(VerificationStatus.CONVERGED,firstState.getVerificationStatus());
        long reportedCount=transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size();
        long convergenceCount=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),firstEvent,"RACE_READ",false,
                "BOOLEAN",fixture.read().getKey(),command));
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),firstEvent.minusSeconds(1),"RACE_READ",false,
                "BOOLEAN",fixture.read().getKey(),command));
        assertEquals(true,reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId())
                .orElseThrow().typedValue());
        assertEquals(firstEvent.truncatedTo(java.time.temporal.ChronoUnit.MICROS),reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId())
                .orElseThrow().latestTimestamp());
        assertTrue(firstEvent.isAfter(reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId())
                .orElseThrow().latestTimestamp()));
        assertEquals(firstReceivedAt,reported.findByDeviceIdAndStateKey(fixture.device(),
                "binding:"+fixture.readBinding().getId()).orElseThrow().receivedAt());
        assertEquals(true,twinController.getTwin(fixture.device()).getBody().effectiveState()
                .get("binding:"+fixture.readBinding().getId()).value());
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals(evidence,verification.findById(fixture.id()).orElseThrow().getLastReadbackTelemetryId());
        assertEquals(reportedCount,transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size());
        assertEquals(convergenceCount,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
    }

    @Test void newerQualifiedSameValueEchoConvergesWithoutReportedValueTransition() {
        var fixture=booleanFixture();
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),Instant.now().minusSeconds(1),
                "RACE_READ",true,"BOOLEAN",fixture.read().getKey()));
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        assertEquals(VerificationStatus.PENDING,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        long reportedBefore=transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size();
        long convergenceBefore=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        assertEquals(1,reportedBefore);
        readback(fixture,true,command,Instant.now());
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.CONVERGED,state.getVerificationStatus());
        assertEquals("MATCH",state.getComparisonResult());
        assertNotNull(state.getLastReadbackTelemetryId());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(reportedBefore,transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size());
        var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
        assertEquals(convergenceBefore+1,history.size());
        assertEquals(1,history.stream().filter(t->"PENDING".equals(t.getPreviousVerificationStatus())
                &&"CONVERGED".equals(t.getNewVerificationStatus())
                &&command.equals(t.getVerificationCommandId())
                &&state.getLastReadbackTelemetryId().equals(t.getReadbackTelemetryId())).count());
        assertEquals(true,reported.findByDeviceIdAndStateKey(fixture.device(),
                "binding:"+fixture.readBinding().getId()).orElseThrow().typedValue());
    }

    @Test void numericReadbackUsesExplicitToleranceAndRejectsInvalidUnitOrTolerance() {
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Number verify","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var equipment=devices.save(new Device(UUID.randomUUID(),space,"Numeric actuator",DeviceType.CUSTOM,
                "NEXUS","TEST","P24-NUMBER-"+UUID.randomUUID(),null,DeviceMode.SIMULATED));
        equipment.update("Numeric actuator",DeviceStatus.ACTIVE,null);
        UUID device=devices.save(equipment).getId();
        var write=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"number_write_"+UUID.randomUUID().toString().replace("-", ""),
                "Numeric write",null,CapabilityDirection.WRITE,CapabilityValueType.NUMBER,"PERCENT",
                List.of("PERCENT"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var read=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"number_read_"+UUID.randomUUID().toString().replace("-", ""),
                "Numeric readback",null,CapabilityDirection.READ,CapabilityValueType.NUMBER,"PERCENT",
                List.of("PERCENT"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var writeBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,write,null,true,"PERCENT",
                Map.of(),Map.of("commandCapability","NUMBER"),now));
        var readBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,read,null,true,"PERCENT",
                Map.of(),Map.of("telemetryKey","NUMBER_READ"),now));
        var policy=new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"NUMERIC_TOLERANCE",new java.math.BigDecimal("0.20"),"COMMAND_ECHO",30);
        assertThrows(IllegalArgumentException.class,()->mappings.addDevice(device,
                new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                        "IDENTITY",null,"NUMERIC_TOLERANCE",new java.math.BigDecimal("-0.01"),"COMMAND_ECHO",30)));
        readBinding.update(true,"CELSIUS",Map.of(),Map.of("telemetryKey","NUMBER_READ"),Instant.now());
        bindings.saveAndFlush(readBinding);
        assertThrows(IllegalArgumentException.class,()->mappings.addDevice(device,policy));
        readBinding.update(true,"PERCENT",Map.of(),Map.of("telemetryKey","NUMBER_READ"),Instant.now());
        bindings.saveAndFlush(readBinding);
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        var mapping=mappings.addDevice(device,policy);
        UUID command=UUID.randomUUID();
        var value=new java.math.BigDecimal("10.00");
        var request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",device.toString(),"capabilityKey",write.getKey(),"command","SET","value",value));
        when(executor.prepare(eq(request))).thenReturn(new CommandExecutor.PreparedCommand(device,writeBinding.getId(),null,
                write.getId(),write.getKey(),CapabilityValueType.NUMBER,"PERCENT","","NUMBER","SET",value));
        acceptance.accept(request,new ActionContext(null,null),now);
        var id=new VerificationStateId(device,"binding:"+writeBinding.getId(),"");
        assertEquals(mapping.getId(),verification.findById(id).orElseThrow().getMappingId());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(1),"NUMBER_READ",
                new java.math.BigDecimal("10.19"),"PERCENT",read.getKey(),command));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(2),"NUMBER_READ",
                new java.math.BigDecimal("10.21"),"PERCENT",read.getKey(),command));
        assertEquals(VerificationStatus.DIVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(3),"NUMBER_READ",
                new java.math.BigDecimal("10.20"),"PERCENT",read.getKey(),command));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        telemetry.processTelemetry(new TelemetryRequest(device,now.plusSeconds(4),"NUMBER_READ",
                new java.math.BigDecimal("10.00"),"PERCENT",read.getKey(),command));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(id).orElseThrow().getVerificationStatus());
        assertEquals(2,transitionsFor(device,TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"CONVERGED".equals(t.getNewVerificationStatus())).count());
        assertEquals(command,desired.findById(new DesiredTwinStateId(device,id.targetKey(),"")).orElseThrow().getCommandId());
    }

    @Test void unknownConnectivityMatchingEchoStaysPendingUntilNewGoodObservation() {
        var fixture=booleanFixture(false);
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        echo(fixture,true,command);
        var pending=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,pending.getVerificationStatus());
        assertNull(pending.getLastReadbackTelemetryId());
        assertNull(pending.getComparisonResult());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        var unknownApi=twinController.getTwin(fixture.device()).getBody();
        assertNull(unknownApi.verification().get(fixture.id().targetKey()).readbackStateQuality());
        assertEquals(TwinStateQuality.UNKNOWN,unknownApi.effectiveState()
                .get("binding:"+fixture.readBinding().getId()).stateQuality().state());
        assertEquals(true,reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId())
                .orElseThrow().typedValue());
        connectivity.applyObservedStatus(fixture.device(),DeviceConnectivityStatus.ONLINE);
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),Instant.now().plusSeconds(1),"RACE_READ",true,
                "BOOLEAN",fixture.read().getKey(),command));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
    }

    @Test void offlineMatchingEchoLeavesPendingWithNoQualifiedComparisonOrHistory() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        connectivity.applyObservedStatus(fixture.device(),DeviceConnectivityStatus.OFFLINE);
        echo(fixture,true,command);
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,state.getVerificationStatus());
        assertNull(state.getComparisonResult());
        assertNull(state.getLastReadbackTelemetryId());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        var offlineApi=twinController.getTwin(fixture.device()).getBody();
        assertNull(offlineApi.verification().get(fixture.id().targetKey()).readbackStateQuality());
        assertEquals(TwinStateQuality.OFFLINE,offlineApi.effectiveState()
                .get("binding:"+fixture.readBinding().getId()).stateQuality().state());
        connectivity.applyObservedStatus(fixture.device(),DeviceConnectivityStatus.ONLINE);
        readback(fixture,true,command,Instant.now().plusSeconds(1));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
    }

    @Test void disablingReadBindingMakesActiveConvergenceUnverifiedWithoutClearingDesired() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        echo(fixture,true,command);
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        long reportedBefore=transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size();
        bindingManagement.update(fixture.device(),fixture.readBinding().getId(),
                new com.nexus.device.binding.api.BindingMutationRequest(null,false,null,null,null,null));
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.UNVERIFIED,state.getVerificationStatus());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(reportedBefore,transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size());
        assertEquals(1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"UNVERIFIED".equals(t.getNewVerificationStatus())).count());
    }

    @Test void qualifiedMismatchBeforeDeadlineRetainsPendingAndWritesNoConvergenceTransition() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        var initial=verification.findById(fixture.id()).orElseThrow();
        Instant deadline=initial.getVerificationDeadlineAt();
        assertTrue(deadline.isAfter(Instant.now()));
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        echo(fixture,false,command);
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,state.getVerificationStatus());
        assertEquals(deadline,state.getVerificationDeadlineAt());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(fixture.mapping().getId(),state.getMappingId());
        assertEquals("MISMATCH",state.getComparisonResult());
        assertEquals("QUALIFIED_READBACK_MISMATCH",state.getDiscrepancyReason());
        assertEquals("COMMAND_ECHO",state.getReadbackEvidence());
        assertEquals("COMMAND_ID_MATCH",state.getReadbackAttribution());
        assertEquals(fixture.readBinding().getId(),state.getLastReadbackBindingId());
        assertEquals(false,reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId()).orElseThrow().typedValue());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        var api=twinController.getTwin(fixture.device()).getBody().verification().get(fixture.id().targetKey());
        assertEquals("PENDING",api.status());
        assertEquals("MISMATCH",api.comparisonResult());
        assertEquals(TwinStateQuality.GOOD,api.readbackStateQuality().state());
    }

    @Test void workerAtControlledExpiredDeadlineTurnsPriorMismatchIntoOneDivergence() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        echo(fixture,false,command);
        var pending=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,pending.getVerificationStatus());
        Instant deadline=pending.getVerificationDeadlineAt();
        UUID evidence=pending.getLastReadbackTelemetryId();
        long reportedBefore=transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size();
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        var controlled=new VerificationService(verification,runtimeMappings,desired,bindings,devices,transitions,
                qualityEvaluator,readbackComparison,java.time.Clock.fixed(deadline.plusNanos(1),java.time.ZoneOffset.UTC));
        var worker=new VerificationDeadlineWorker(controlled);
        var transaction=new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored->worker.reconcile());
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.DIVERGED,state.getVerificationStatus());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(fixture.mapping().getId(),state.getMappingId());
        assertEquals(evidence,state.getLastReadbackTelemetryId());
        assertEquals("MISMATCH",state.getComparisonResult());
        assertEquals("COMMAND_ECHO",state.getReadbackEvidence());
        assertEquals("COMMAND_ID_MATCH",state.getReadbackAttribution());
        assertEquals(reportedBefore,transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
        assertEquals(before+1,history.size());
        var change=history.stream().filter(t->"DIVERGED".equals(t.getNewVerificationStatus())).findFirst().orElseThrow();
        assertEquals("PENDING",change.getPreviousVerificationStatus());
        assertEquals(command,change.getVerificationCommandId());
        assertEquals(fixture.mapping().getId(),change.getVerificationMappingId());
        assertEquals(evidence,change.getReadbackTelemetryId());
        assertEquals(fixture.readBinding().getId(),change.getReadbackBindingId());
        assertEquals("MISMATCH",change.getComparisonResult());
        assertEquals("COMMAND_ECHO",change.getReadbackEvidence());
        assertEquals("COMMAND_ID_MATCH",change.getReadbackAttribution());
        transaction.executeWithoutResult(ignored->worker.reconcile());
        assertEquals(before+1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
    }

    @Test void failedPublicationLeavesDesiredButEndsPendingVerificationWithoutReportedMutation() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        assertEquals(VerificationStatus.PENDING,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        reconciliation.commandFailed(fixture.device(),command,"MQTT_PUBLISH_FAILED");
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.UNVERIFIED,state.getVerificationStatus());
        assertEquals("MQTT_PUBLISH_FAILED",state.getDiscrepancyReason());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertTrue(reported.findByDeviceId(fixture.device()).isEmpty());
        assertEquals(0,transitionsFor(fixture.device(),TwinTransitionType.REPORTED_STATE_CHANGED).size());
        assertEquals(1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"UNVERIFIED".equals(t.getNewVerificationStatus())).count());
    }

    @Test void failedMqttPublishKeepsAcceptedDesiredAndNeverClaimsConvergence() {
        var fixture=booleanFixture();
        UUID command=UUID.randomUUID();
        var request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",fixture.device().toString(),"capabilityKey","led","command","SET_STATE","value",true));
        var prepared=new CommandExecutor.PreparedCommand(fixture.device(),fixture.writeBinding().getId(),null,
                fixture.write().getId(),"led",CapabilityValueType.BOOLEAN,"BOOLEAN","","LED","SET_STATE",true);
        when(executor.prepare(eq(request))).thenReturn(prepared);
        when(executor.publishPrepared(eq(request),eq(prepared))).thenReturn(new ActionExecutionResult(command,request,
                ExecutionStatus.FAILURE,Instant.now(),"MQTT unavailable"));
        var outcome=dispatchCoordinator.dispatch(request,new ActionContext(null,null),"failed-publish");
        assertTrue(outcome.accepted());
        var action=actions.findById(command).orElseThrow();
        assertEquals(ExecutionStatus.FAILURE,action.getStatus());
        assertEquals(CommandStatus.FAILED,CommandStatus.from(action,java.time.Clock.systemUTC()));
        assertNull(action.getPublishedAt());assertNull(action.getAckStatus());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.UNVERIFIED,state.getVerificationStatus());
        assertEquals("MQTT_PUBLICATION_FAILED",state.getDiscrepancyReason());
        assertTrue(reported.findByDeviceId(fixture.device()).isEmpty());
        var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
        assertEquals(2,history.size());
        assertEquals(1,history.stream().filter(t->"PENDING".equals(t.getPreviousVerificationStatus())
                &&"UNVERIFIED".equals(t.getNewVerificationStatus())&&command.equals(t.getVerificationCommandId())).count());
        assertEquals("UNVERIFIED",twinController.getTwin(fixture.device()).getBody().verification()
                .get(fixture.id().targetKey()).status());
    }

    @Test void rejectedAckPreservesPublicationAndDeviceTimestampWithoutReportedMutation() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        Instant publishedAt=Instant.now();
        actions.recordSuccessfulPublication(command,publishedAt,publishedAt.plusSeconds(30),"published");
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        long deviceEpoch=Instant.now().minusSeconds(3).getEpochSecond();
        ackHandler.handle("nexus/devices/"+fixture.device()+"/commands/ack",("{\"commandId\":\""+command
                +"\",\"status\":\"REJECTED\",\"timestamp\":"+deviceEpoch+",\"reason\":\"DEVICE_REFUSED\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var action=actions.findById(command).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS,action.getStatus());
        assertEquals(CommandStatus.REJECTED,CommandStatus.from(action,java.time.Clock.systemUTC()));
        assertEquals(AcknowledgementStatus.REJECTED,action.getAckStatus());
        assertNotNull(action.getAckBackendReceivedAt());
        assertEquals(Instant.ofEpochSecond(deviceEpoch),action.getAckDeviceTimestamp());
        assertEquals(action.getAckDeviceTimestamp(),action.getAckReceivedAt());
        assertEquals("DEVICE_REFUSED",action.getAckMessage());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertTrue(reported.findByDeviceId(fixture.device()).isEmpty());
        assertEquals("DEVICE_COMMAND_REJECTED",verification.findById(fixture.id()).orElseThrow().getDiscrepancyReason());
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
        assertEquals(before+1,history.size());
        assertEquals(1,history.stream().filter(t->"PENDING".equals(t.getPreviousVerificationStatus())
                &&"UNVERIFIED".equals(t.getNewVerificationStatus())&&command.equals(t.getVerificationCommandId())).count());
        assertEquals("UNVERIFIED",twinController.getTwin(fixture.device()).getBody().verification()
                .get(fixture.id().targetKey()).status());
    }

    @Test void matchingEchoConvergesWithoutAckWhileDeliveryStatusCanLaterTimeOut() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        Instant publishedAt=Instant.now();
        Instant ackDeadline=publishedAt.plusSeconds(30);
        actions.recordSuccessfulPublication(command,publishedAt,ackDeadline,"published");
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        echo(fixture,true,command);
        var action=actions.findById(command).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS,action.getStatus());
        assertNull(action.getAckStatus());
        assertEquals(CommandStatus.PUBLISHED,CommandStatus.from(action,java.time.Clock.fixed(publishedAt.plusSeconds(1),java.time.ZoneOffset.UTC)));
        assertEquals(CommandStatus.TIMED_OUT,CommandStatus.from(action,java.time.Clock.fixed(ackDeadline.plusSeconds(1),java.time.ZoneOffset.UTC)));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(before+1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        assertEquals(1,actions.findAll().stream().filter(a->fixture.device().equals(a.getDeviceId())).count());
        var api=twinController.getTwin(fixture.device()).getBody();
        assertEquals("PUBLISHED",api.desiredState().get(fixture.id().targetKey()).commandStatus());
        assertEquals("CONVERGED",api.verification().get(fixture.id().targetKey()).status());
    }

    @Test void duplicateAndConflictingAckLeaveFirstPersistedFactsAndVerificationUntouched() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        Instant publishedAt=Instant.now();actions.recordSuccessfulPublication(command,publishedAt,publishedAt.plusSeconds(30),"published");
        long deviceEpoch=Instant.now().minusSeconds(5).getEpochSecond();
        String topic="nexus/devices/"+fixture.device()+"/commands/ack";
        String first="{\"commandId\":\""+command+"\",\"status\":\"ACCEPTED\",\"timestamp\":"+deviceEpoch
                +",\"reason\":\"READY\"}";
        ackHandler.handle(topic,first.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var authoritative=actions.findById(command).orElseThrow();
        var backendAt=authoritative.getAckBackendReceivedAt();
        var deviceAt=authoritative.getAckDeviceTimestamp();
        var legacyAt=authoritative.getAckReceivedAt();
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        ackHandler.handle(topic,first.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ackHandler.handle(topic,first.replace("ACCEPTED","REJECTED").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var after=actions.findById(command).orElseThrow();
        assertEquals(AcknowledgementStatus.ACCEPTED,after.getAckStatus());
        assertEquals(backendAt,after.getAckBackendReceivedAt());
        assertEquals(deviceAt,after.getAckDeviceTimestamp());
        assertEquals(legacyAt,after.getAckReceivedAt());
        assertEquals("READY",after.getAckMessage());
        assertEquals(CommandStatus.ACKNOWLEDGED,CommandStatus.from(after,java.time.Clock.systemUTC()));
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(VerificationStatus.PENDING,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
    }

    @Test void firstLateAckForSupersededCommandCannotMutateCurrentVerification() {
        var fixture=booleanFixture();
        UUID old=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        UUID current=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        ackHandler.handle("nexus/devices/"+fixture.device()+"/commands/ack",("{\"commandId\":\""+old
                +"\",\"status\":\"ACCEPTED\",\"timestamp\":"+Instant.now().getEpochSecond()+"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(AcknowledgementStatus.ACCEPTED,actions.findById(old).orElseThrow().getAckStatus());
        assertEquals(CommandStatus.ACKNOWLEDGED,CommandStatus.from(actions.findById(old).orElseThrow(),java.time.Clock.systemUTC()));
        assertEquals(current,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(current,verification.findById(fixture.id()).orElseThrow().getDesiredCommandId());
        assertEquals(VerificationStatus.PENDING,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertTrue(reported.findByDeviceId(fixture.device()).isEmpty());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
    }

    @Test void oldEchoAfterSupersessionUpdatesOnlyReportedThenCurrentEchoConverges() {
        var fixture=booleanFixture();
        UUID old=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        UUID current=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        echo(fixture,false,old);
        assertEquals(false,reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId()).orElseThrow().typedValue());
        assertEquals(current,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        assertEquals(current,verification.findById(fixture.id()).orElseThrow().getDesiredCommandId());
        assertEquals(VerificationStatus.PENDING,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),Instant.now().plusSeconds(1),"RACE_READ",true,
                "BOOLEAN",fixture.read().getKey(),current));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals(before+1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
    }

    @Test void divergedRecoversToConvergedWithOneAttributedTransition() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        Instant event=Instant.now();
        readback(fixture,true,command,event);
        readback(fixture,false,command,event.plusSeconds(1));
        assertEquals(VerificationStatus.DIVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        readback(fixture,true,command,event.plusSeconds(2));
        assertRecovery(fixture,command,before,"DIVERGED","CONVERGED","MATCH");
    }

    @Test void timedOutRecoversToConvergedWithoutErasingOriginalTimeout() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        expire(fixture.id());
        reconciliation.deadline(fixture.id());
        assertEquals(VerificationStatus.TIMED_OUT,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        readback(fixture,true,command,Instant.now());
        assertRecovery(fixture,command,before,"TIMED_OUT","CONVERGED","MATCH");
        assertEquals(1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"TIMED_OUT".equals(t.getNewVerificationStatus())&&command.equals(t.getVerificationCommandId())).count());
    }

    @Test void timedOutRecoversToDivergedWithOneAttributedMismatchTransition() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        expire(fixture.id());
        reconciliation.deadline(fixture.id());
        assertEquals(VerificationStatus.TIMED_OUT,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        readback(fixture,false,command,Instant.now());
        assertRecovery(fixture,command,before,"TIMED_OUT","DIVERGED","MISMATCH");
        assertEquals(1,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->"TIMED_OUT".equals(t.getNewVerificationStatus())&&command.equals(t.getVerificationCommandId())).count());
    }

    private void assertRecovery(BooleanFixture fixture,UUID command,long before,String previous,String next,String comparison){
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.valueOf(next),state.getVerificationStatus());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals(fixture.mapping().getId(),state.getMappingId());
        assertEquals("COMMAND_ECHO",state.getReadbackEvidence());
        assertEquals("COMMAND_ID_MATCH",state.getReadbackAttribution());
        assertEquals(comparison,state.getComparisonResult());
        assertEquals(command,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),"")).orElseThrow().getCommandId());
        var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
        assertEquals(before+1,history.size());
        var change=history.stream().filter(t->previous.equals(t.getPreviousVerificationStatus())
                &&next.equals(t.getNewVerificationStatus())).findFirst().orElseThrow();
        assertEquals(command,change.getVerificationCommandId());
        assertEquals(fixture.mapping().getId(),change.getVerificationMappingId());
        assertEquals(state.getLastReadbackTelemetryId(),change.getReadbackTelemetryId());
        assertEquals(fixture.readBinding().getId(),change.getReadbackBindingId());
        assertEquals("COMMAND_ECHO",change.getReadbackEvidence());
        assertEquals("COMMAND_ID_MATCH",change.getReadbackAttribution());
        assertEquals(comparison,change.getComparisonResult());
    }

    private void readback(BooleanFixture fixture,boolean value,UUID command,Instant eventTime){
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),eventTime,"RACE_READ",value,
                "BOOLEAN",fixture.read().getKey(),command));
    }

    @Test void enumExactMatchAndMismatchPreserveTypedMembersThroughDeadline() {
        var fixture=typedFixture(CapabilityValueType.ENUM,true);
        UUID match=acceptTyped(fixture,"ON");
        typedReadback(fixture,"ON",match,Instant.now());
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals("ON",verification.findById(fixture.id()).orElseThrow().getReadbackValue());
        assertEquals("ON",twinController.getTwin(fixture.device()).getBody().desiredState()
                .get(fixture.id().targetKey()).typedValue());
        UUID mismatch=acceptTyped(fixture,"ON");
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        typedReadback(fixture,"OFF",mismatch,Instant.now().plusSeconds(1));
        var pending=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,pending.getVerificationStatus());
        assertEquals("MISMATCH",pending.getComparisonResult());
        assertEquals("OFF",pending.getReadbackValue());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        reconcileAfter(fixture.id(),pending.getVerificationDeadlineAt());
        assertTypedDivergence(fixture,mismatch,before,"ON","OFF");
    }

    @Test void stringExactComparisonIsCaseSensitiveThroughDeadline() {
        var fixture=typedFixture(CapabilityValueType.STRING,true);
        UUID match=acceptTyped(fixture,"ON");
        typedReadback(fixture,"ON",match,Instant.now());
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        UUID mismatch=acceptTyped(fixture,"ON");
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        typedReadback(fixture,"on",mismatch,Instant.now().plusSeconds(1));
        var pending=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,pending.getVerificationStatus());
        assertEquals("MISMATCH",pending.getComparisonResult());
        assertEquals("on",pending.getReadbackValue());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        reconcileAfter(fixture.id(),pending.getVerificationDeadlineAt());
        assertTypedDivergence(fixture,mismatch,before,"ON","on");
    }

    @Test void objectWithoutRegisteredComparatorRejectsMappingAndCannotConverge() {
        var fixture=typedFixture(CapabilityValueType.OBJECT,false);
        assertThrows(IllegalArgumentException.class,()->mappings.addDevice(fixture.device(),
                new ReadbackMappingService.Policy(fixture.writeBinding().getId(),"",fixture.readBinding().getId(),
                        "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",30)));
        assertTrue(runtimeMappings.findByDeviceId(fixture.device()).isEmpty());
        var value=Map.of("state",(Object)true);
        UUID command=acceptTyped(fixture,value);
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.UNVERIFIED,state.getVerificationStatus());
        assertNull(state.getMappingId());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        typedReadback(fixture,value,command,Instant.now());
        assertEquals(VerificationStatus.UNVERIFIED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        assertEquals("UNVERIFIED",twinController.getTwin(fixture.device()).getBody().verification()
                .get(fixture.id().targetKey()).status());
    }

    private record TypedFixture(UUID device,CapabilityValueType type,CapabilityDefinition write,CapabilityDefinition read,
            DeviceCapabilityBinding writeBinding,DeviceCapabilityBinding readBinding,VerificationStateId id) {}
    private TypedFixture typedFixture(CapabilityValueType type,boolean mapped){
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Typed verify","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var equipment=devices.save(new Device(UUID.randomUUID(),space,"Typed actuator",DeviceType.CUSTOM,
                "NEXUS","TEST","P24-TYPED-"+UUID.randomUUID(),null,DeviceMode.SIMULATED));
        equipment.update("Typed actuator",DeviceStatus.ACTIVE,null);
        UUID device=devices.save(equipment).getId();
        String suffix=UUID.randomUUID().toString().replace("-", "");
        List<String> members=type==CapabilityValueType.ENUM?List.of("ON","OFF"):List.of();
        var write=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"typed_write_"+suffix,
                "Typed write",null,CapabilityDirection.WRITE,type,null,List.of(),null,null,members,
                List.of("SET_STATE"),"actuator",Map.of(),Map.of()));
        var read=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"typed_read_"+suffix,
                "Typed read",null,CapabilityDirection.READ,type,null,List.of(),null,null,members,
                List.of(),"actuator",Map.of(),Map.of()));
        var writeBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,write,null,true,null,
                Map.of(),Map.of("commandCapability","TYPED"),now));
        var readBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,read,null,true,null,
                Map.of(),Map.of("telemetryKey","TYPED_READ"),now));
        connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        if(mapped)mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",30));
        return new TypedFixture(device,type,write,read,writeBinding,readBinding,
                new VerificationStateId(device,"binding:"+writeBinding.getId(),""));
    }
    private UUID acceptTyped(TypedFixture fixture,Object value){
        UUID command=UUID.randomUUID();
        var request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",fixture.device().toString(),"capabilityKey",fixture.write().getKey(),
                        "command","SET_STATE","value",value));
        when(executor.prepare(eq(request))).thenReturn(new CommandExecutor.PreparedCommand(fixture.device(),
                fixture.writeBinding().getId(),null,fixture.write().getId(),fixture.write().getKey(),fixture.type(),null,
                "","TYPED","SET_STATE",value));
        acceptance.accept(request,new ActionContext(null,null),Instant.now());
        return command;
    }
    private void typedReadback(TypedFixture fixture,Object value,UUID command,Instant eventTime){
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),eventTime,"TYPED_READ",value,
                null,fixture.read().getKey(),command));
    }
    private void reconcileAfter(VerificationStateId id,Instant deadline){
        var controlled=new VerificationService(verification,runtimeMappings,desired,bindings,devices,transitions,
                qualityEvaluator,readbackComparison,java.time.Clock.fixed(deadline.plusNanos(1),java.time.ZoneOffset.UTC));
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored->new VerificationDeadlineWorker(controlled).reconcile());
    }
    private void assertTypedDivergence(TypedFixture fixture,UUID command,long before,String expected,String observed){
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.DIVERGED,state.getVerificationStatus());
        assertEquals(command,state.getDesiredCommandId());
        assertEquals("MISMATCH",state.getComparisonResult());
        assertEquals(observed,state.getReadbackValue());
        assertEquals("COMMAND_ECHO",state.getReadbackEvidence());
        assertEquals(expected,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),""))
                .orElseThrow().typedValue());
        assertEquals(observed,reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId())
                .orElseThrow().typedValue());
        var history=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED);
        assertEquals(before+1,history.size());
        var terminal=history.stream().filter(t->"PENDING".equals(t.getPreviousVerificationStatus())
                &&"DIVERGED".equals(t.getNewVerificationStatus())).findFirst().orElseThrow();
        assertEquals(command,terminal.getVerificationCommandId());
        assertEquals(state.getMappingId(),terminal.getVerificationMappingId());
        assertEquals(state.getLastReadbackTelemetryId(),terminal.getReadbackTelemetryId());
        assertEquals("MISMATCH",terminal.getComparisonResult());
    }

    @Test void staleMatchingObservationCannotConvergeButLaterFreshEchoCan() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),Instant.now(),"RACE_READ",true,
                "BOOLEAN",fixture.read().getKey()));
        var oldReported=reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId()).orElseThrow();
        var staleRecord=new TelemetryRecord(UUID.randomUUID(),fixture.device(),oldReported.latestTimestamp(),"RACE_READ",
                true,"BOOLEAN",fixture.read().getId(),fixture.readBinding().getId(),fixture.read().getKey(),
                CapabilityValueType.BOOLEAN,com.nexus.device.binding.runtime.CapabilityResolutionSource.GENERIC_BINDING);
        Instant evaluatedAt=oldReported.receivedAt().plusSeconds(3600);
        assertEquals(TwinStateQuality.STALE,qualityEvaluator.evaluate(oldReported,evaluatedAt).state());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        reconciliation.observed(staleRecord,oldReported,command,"stale-observation",
                evaluatedAt);
        var pending=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.PENDING,pending.getVerificationStatus());
        assertNull(pending.getLastReadbackTelemetryId());
        assertNull(pending.getComparisonResult());
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),Instant.now().plusSeconds(1),"RACE_READ",true,
                "BOOLEAN",fixture.read().getKey(),command));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
    }

    @Test void qualityDecayDoesNotRewriteLastConvergenceAndLaterGoodReadbackMayDriftAndRecover() {
        var fixture=booleanFixture();
        UUID command=accept(fixture.device(),fixture.writeBinding(),fixture.write(),Instant.now());
        Instant event=Instant.now();
        readback(fixture,true,command,event);
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(VerificationStatus.CONVERGED,state.getVerificationStatus());
        long before=transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size();
        var readTwin=reported.findByDeviceIdAndStateKey(fixture.device(),"binding:"+fixture.readBinding().getId()).orElseThrow();
        assertQualityProjection(fixture,command,readTwin,
                qualityEvaluator.evaluate(readTwin,readTwin.receivedAt().plusSeconds(3600)),TwinStateQuality.STALE);
        connectivity.applyObservedStatus(fixture.device(),DeviceConnectivityStatus.OFFLINE);
        assertEquals(TwinStateQuality.OFFLINE,twinController.getTwin(fixture.device()).getBody().verification()
                .get(fixture.id().targetKey()).readbackStateQuality().state());
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        var unknown=new TwinStateQualityEvidence(TwinStateQuality.UNKNOWN,"CONNECTIVITY_UNOBSERVED",
                Instant.now(),readTwin.receivedAt(),30,"UNKNOWN");
        assertQualityProjection(fixture,command,readTwin,unknown,TwinStateQuality.UNKNOWN);
        assertEquals(before,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).size());
        connectivity.applyObservedStatus(fixture.device(),DeviceConnectivityStatus.ONLINE);
        readback(fixture,false,command,event.plusSeconds(1));
        assertEquals(VerificationStatus.DIVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
        readback(fixture,true,command,event.plusSeconds(2));
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
    }

    private void assertQualityProjection(BooleanFixture fixture,UUID command,DigitalTwin readTwin,
            TwinStateQualityEvidence evidence,TwinStateQuality expected){
        var action=actions.findById(command).orElseThrow();
        var response=com.nexus.twin.api.dto.DigitalTwinResponse.from(List.of(readTwin),
                desired.findByDeviceId(fixture.device()),Map.of(command,action),Map.of(readTwin.stateKey(),evidence),
                verification.findByDeviceId(fixture.device()),runtimeMappings.findByDeviceId(fixture.device()));
        var projected=response.verification().get(fixture.id().targetKey());
        assertEquals("CONVERGED",projected.status());
        assertEquals(expected,projected.readbackStateQuality().state());
        assertEquals(VerificationStatus.CONVERGED,verification.findById(fixture.id()).orElseThrow().getVerificationStatus());
    }

    private record BooleanFixture(UUID device,CapabilityDefinition write,CapabilityDefinition read,
            DeviceCapabilityBinding writeBinding,DeviceCapabilityBinding readBinding,
            DeviceReadbackMapping mapping,VerificationStateId id) {}

    private BooleanFixture booleanFixture(){return booleanFixture(true);}
    private BooleanFixture booleanFixture(boolean online){
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(),"Race verify","test",now,now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        var equipment=devices.save(new Device(UUID.randomUUID(),space,"LED",DeviceType.CUSTOM,
                "NEXUS","TEST","P24-RACE-"+UUID.randomUUID(),null,DeviceMode.SIMULATED));
        equipment.update("LED",DeviceStatus.ACTIVE,null);
        UUID device=devices.save(equipment).getId();
        var write=definitions.findByKey("led").orElseThrow();
        var read=definitions.save(new CapabilityDefinition(UUID.randomUUID(),"race_read_"+UUID.randomUUID().toString().replace("-", ""),
                "LED readback",null,CapabilityDirection.READ,CapabilityValueType.BOOLEAN,"BOOLEAN",
                List.of("BOOLEAN"),null,null,List.of(),List.of(),"actuator",Map.of(),Map.of()));
        var writeBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,write,null,true,"BOOLEAN",
                Map.of(),Map.of("commandCapability","LED"),now));
        var readBinding=bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(),device,read,null,true,"BOOLEAN",
                Map.of(),Map.of("telemetryKey","RACE_READ"),now));
        if(online)connectivity.applyObservedStatus(device,DeviceConnectivityStatus.ONLINE);
        var mapping=mappings.addDevice(device,new ReadbackMappingService.Policy(writeBinding.getId(),"",readBinding.getId(),
                "IDENTITY",null,"EXACT",null,"COMMAND_ECHO",30));
        return new BooleanFixture(device,write,read,writeBinding,readBinding,mapping,
                new VerificationStateId(device,"binding:"+writeBinding.getId(),""));
    }
    private void echo(BooleanFixture fixture,boolean value,UUID command){
        telemetry.processTelemetry(new TelemetryRequest(fixture.device(),Instant.now(),"RACE_READ",value,
                "BOOLEAN",fixture.read().getKey(),command));
    }
    private void assertRaceFinalOwner(BooleanFixture fixture,UUID newer){
        var state=verification.findById(fixture.id()).orElseThrow();
        assertEquals(newer,state.getDesiredCommandId());
        assertEquals(VerificationStatus.PENDING,state.getVerificationStatus());
        assertEquals(newer,desired.findById(new DesiredTwinStateId(fixture.device(),fixture.id().targetKey(),""))
                .orElseThrow().getCommandId());
        assertEquals(1,verification.findByDeviceId(fixture.device()).size());
        assertEquals(0,transitionsFor(fixture.device(),TwinTransitionType.CONVERGENCE_CHANGED).stream()
                .filter(t->newer.equals(t.getVerificationCommandId())
                        &&"CONVERGED".equals(t.getNewVerificationStatus())).count());
    }
    private static void await(java.util.concurrent.CountDownLatch latch){
        try { assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS)); }
        catch(InterruptedException interrupted){ Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
    }
    private void awaitDeviceLockWait() throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try(var connection=dataSource.getConnection();var query=connection.createStatement()){
            while(System.nanoTime()<deadline){
                try(var rows=query.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                        +"WHERE wait_event_type = 'Lock' AND state = 'active' "
                        +"AND query ILIKE '%devices%' AND pid <> pg_backend_pid())")){
                    rows.next();
                    if(rows.getBoolean(1))return;
                }
            }
        }
        fail("competing operation never waited on the PostgreSQL device-row lock");
    }
    private void expire(VerificationStateId id){
        var pending=verification.findById(id).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(pending,"verificationDeadlineAt",Instant.now().minusSeconds(1));
        verification.saveAndFlush(pending);
    }
    private void race(Runnable left,Runnable right) throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();left.run();return null;});
            var b=pool.submit(()->{start.await();right.run();return null;});
            start.countDown();
            a.get(20,java.util.concurrent.TimeUnit.SECONDS);
            b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @Autowired DeviceReadbackMappingRepository runtimeMappings;
    @Autowired BindingManagementService bindingManagement;
    @Autowired DigitalTwinController twinController;
    private UUID mappingsDeviceId(UUID device,UUID write){
        return runtimeMappings.findByDeviceIdAndWriteBindingIdAndWriteSubtargetKeyAndEnabledTrue(device,write,"")
                .orElseThrow().getId();
    }

    private UUID accept(UUID device,DeviceCapabilityBinding binding,CapabilityDefinition definition,Instant requestedAt){
        UUID command=UUID.randomUUID();
        ActionExecutionRequest request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",device.toString(),"capabilityKey","led","command","SET_STATE","value",true));
        when(executor.prepare(eq(request))).thenReturn(new CommandExecutor.PreparedCommand(device,binding.getId(),null,
                definition.getId(),"led",CapabilityValueType.BOOLEAN,"BOOLEAN","","LED","SET_STATE",true));
        acceptance.accept(request,new ActionContext(null,null),requestedAt);
        return command;
    }

    private UUID acceptRelay(UUID device,DeviceCapabilityBinding binding,CapabilityDefinition definition,int channel,boolean value){
        UUID command=UUID.randomUUID();
        var relayValue=Map.of("channel",(Object)channel,"state",value);
        ActionExecutionRequest request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",device.toString(),"capabilityKey","relay","command","SET_STATE","value",relayValue));
        when(executor.prepare(eq(request))).thenReturn(new CommandExecutor.PreparedCommand(device,binding.getId(),null,
                definition.getId(),"relay",CapabilityValueType.OBJECT,"BOOLEAN","channel:"+channel,"RELAY","SET_STATE",relayValue));
        acceptance.accept(request,new ActionContext(null,null),Instant.now());
        return command;
    }
    private List<TwinStateTransition> transitionsFor(UUID device,TwinTransitionType type){
        return transitions.findAll().stream().filter(t->t.getDeviceId().equals(device)&&t.getTransitionType()==type).toList();
    }
}
