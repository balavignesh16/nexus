package com.nexus.twin.application;

import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.twin.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

class VerificationQualityGateTest {
    @Test void matchingEchoCannotBeEvaluatedWhenCurrentReadbackQualityIsStaleOfflineOrUnknown() {
        for (TwinStateQuality degraded : new TwinStateQuality[]{TwinStateQuality.STALE,
                TwinStateQuality.OFFLINE,TwinStateQuality.UNKNOWN}) {
            var current=mock(CurrentTwinVerificationRepository.class);
            var mappings=mock(DeviceReadbackMappingRepository.class);
            var desired=mock(DesiredTwinStateRepository.class);
            var bindings=mock(DeviceCapabilityBindingRepository.class);
            var devices=mock(DeviceRepository.class);
            var transitions=mock(TwinStateTransitionRepository.class);
            var quality=mock(TwinStateQualityEvaluator.class);
            var service=new VerificationService(current,mappings,desired,bindings,devices,transitions,
                    quality,new ReadbackComparison(),Clock.systemUTC());
            var telemetry=mock(TelemetryRecord.class);
            var reported=mock(DigitalTwin.class);
            Instant receivedAt=Instant.now();
            when(telemetry.getBindingId()).thenReturn(UUID.randomUUID());
            when(quality.evaluate(reported,receivedAt)).thenReturn(new TwinStateQualityEvidence(
                    degraded,"DEGRADED",receivedAt,receivedAt,30,degraded.name()));

            service.observed(telemetry,reported,UUID.randomUUID(),"quality-gate",receivedAt);

            verifyNoInteractions(current,mappings,desired,bindings,devices,transitions);
        }
    }
}
