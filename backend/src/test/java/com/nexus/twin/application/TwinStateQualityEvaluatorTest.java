package com.nexus.twin.application;

import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.capability.definition.validation.CapabilityValueValidator;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.connectivity.*;
import com.nexus.twin.domain.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TwinStateQualityEvaluatorTest {
    private static final Instant NOW=Instant.parse("2026-10-06T06:00:00Z");
    private final DeviceConnectivityRegistry connectivity=mock(DeviceConnectivityRegistry.class);
    private final DeviceCapabilityBindingRepository bindings=mock(DeviceCapabilityBindingRepository.class);
    private final TwinStateQualityEvaluator evaluator=new TwinStateQualityEvaluator(connectivity,bindings,
            new FreshnessPolicy(),Clock.fixed(NOW,ZoneOffset.UTC));
    private final UUID deviceId=UUID.randomUUID();

    @Test void unobservedConnectivityIsUnknown(){
        when(connectivity.get(deviceId)).thenReturn(Optional.empty());
        assertEquals(TwinStateQuality.UNKNOWN,evaluator.evaluate(twin(NOW.minusSeconds(1))).state());
    }
    @Test void missingReportedObservationIsExplicitlyUnknown(){
        when(connectivity.get(deviceId)).thenReturn(Optional.of(new DeviceConnectivity(deviceId,DeviceConnectivityStatus.ONLINE,NOW)));
        TwinStateQualityEvidence evidence=evaluator.evaluateMissing(deviceId,null);
        assertEquals(TwinStateQuality.UNKNOWN,evidence.state());
        assertEquals("NO_REPORTED_OBSERVATION",evidence.reason());
        assertEquals(120,evidence.thresholdSeconds());
    }
    @Test void offlineWinsOverFreshness(){
        when(connectivity.get(deviceId)).thenReturn(Optional.of(new DeviceConnectivity(deviceId,DeviceConnectivityStatus.OFFLINE,NOW)));
        assertEquals(TwinStateQuality.OFFLINE,evaluator.evaluate(twin(NOW.minusSeconds(500))).state());
    }
    @Test void thresholdIsInclusiveAndThenBecomesStale(){
        when(connectivity.get(deviceId)).thenReturn(Optional.of(new DeviceConnectivity(deviceId,DeviceConnectivityStatus.ONLINE,NOW)));
        assertEquals(TwinStateQuality.GOOD,evaluator.evaluate(twin(NOW.minusSeconds(120))).state());
        assertEquals(TwinStateQuality.STALE,evaluator.evaluate(twin(NOW.minusSeconds(121))).state());
    }
    @Test void migratedRowWithoutReceiptTimeIsUnknown(){
        when(connectivity.get(deviceId)).thenReturn(Optional.of(new DeviceConnectivity(deviceId,DeviceConnectivityStatus.ONLINE,NOW)));
        DigitalTwin old=new DigitalTwin(deviceId,NOW,"TEMPERATURE",22.0,"C",NOW,Map.of());
        assertEquals(TwinStateQuality.UNKNOWN,evaluator.evaluate(old).state());
    }
    @Test void persistedValueOutsideCurrentDefinitionIsUnknown(){
        UUID definitionId=UUID.randomUUID();
        CapabilityDefinitionRepository definitions=mock(CapabilityDefinitionRepository.class);
        CapabilityDefinition definition=new CapabilityDefinition(definitionId,"temperature","Temperature",null,
                CapabilityDirection.READ,CapabilityValueType.NUMBER,"C",List.of("C"),
                java.math.BigDecimal.ZERO,java.math.BigDecimal.TEN,List.of(),List.of(),"environment",Map.of(),Map.of());
        when(definitions.findById(definitionId)).thenReturn(Optional.of(definition));
        when(connectivity.get(deviceId)).thenReturn(Optional.of(new DeviceConnectivity(deviceId,DeviceConnectivityStatus.ONLINE,NOW)));
        DigitalTwin invalid=new DigitalTwin(deviceId,NOW,"temperature","temperature",22.0,"C",NOW,NOW,
                Map.of(),definitionId,null,"temperature",CapabilityValueType.NUMBER,CapabilityResolutionSource.LEGACY_ADAPTER);
        TwinStateQualityEvaluator validating=new TwinStateQualityEvaluator(connectivity,bindings,new FreshnessPolicy(),
                Clock.fixed(NOW,ZoneOffset.UTC),definitions,new CapabilityValueValidator());
        assertEquals("INVALID_PERSISTED_VALUE",validating.evaluate(invalid).reason());
        assertEquals(TwinStateQuality.UNKNOWN,validating.evaluate(invalid).state());
    }
    private DigitalTwin twin(Instant receivedAt){return new DigitalTwin(deviceId,NOW,"TEMPERATURE","TEMPERATURE",22.0,"C",NOW,
            receivedAt,Map.of(),UUID.randomUUID(),null,"temperature",CapabilityValueType.NUMBER,CapabilityResolutionSource.LEGACY_ADAPTER);}
}
