package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.application.DigitalTwinService;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.nexus.device.binding.runtime.RuntimeCapabilityResolver;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.validation.*;
import com.nexus.device.onboarding.OnboardingLifecycleService;
import com.nexus.device.onboarding.OnboardingTelemetryRejectedException;
import com.nexus.device.binding.BindingConstraintService;
import com.nexus.shared.exception.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final DeviceRepository deviceRepository;
    private final DigitalTwinService digitalTwinService;
    private final RealtimePublisher realtimePublisher;
    private final PipelineService pipelineService;
    private final RuntimeCapabilityResolver capabilityResolver;
    private final CapabilityValueValidator capabilityValueValidator;
    private final OnboardingLifecycleService onboarding;
    private final BindingConstraintService bindingConstraints;

    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository,
                            DigitalTwinService digitalTwinService, RealtimePublisher realtimePublisher,
                            PipelineService pipelineService) {
        this(telemetryRepository,deviceRepository,digitalTwinService,realtimePublisher,pipelineService,null,null);
    }

    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository,
                            DigitalTwinService digitalTwinService, RealtimePublisher realtimePublisher,
                            PipelineService pipelineService, RuntimeCapabilityResolver capabilityResolver,
                            CapabilityValueValidator capabilityValueValidator) {
        this(telemetryRepository, deviceRepository, digitalTwinService, realtimePublisher, pipelineService,
                capabilityResolver, capabilityValueValidator, null, null);
    }

    @Autowired
    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository,
                            DigitalTwinService digitalTwinService, RealtimePublisher realtimePublisher,
                            PipelineService pipelineService, RuntimeCapabilityResolver capabilityResolver,
                            CapabilityValueValidator capabilityValueValidator,
                            OnboardingLifecycleService onboarding, BindingConstraintService bindingConstraints) {
        this.telemetryRepository = telemetryRepository;
        this.deviceRepository = deviceRepository;
        this.digitalTwinService = digitalTwinService;
        this.realtimePublisher = realtimePublisher;
        this.pipelineService = pipelineService;
        this.capabilityResolver = capabilityResolver;
        this.capabilityValueValidator = capabilityValueValidator;
        this.onboarding = onboarding;
        this.bindingConstraints = bindingConstraints;
    }

    @Transactional
    public TelemetryResponse processTelemetry(TelemetryRequest request) {
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("Device", request.deviceId().toString()));

        if (onboarding == null ? device.getStatus() != DeviceStatus.ACTIVE : !onboarding.admitsTelemetry(device)) {
            throw new IllegalStateException("Cannot ingest telemetry for inactive device");
        }

        if (capabilityResolver == null) return processLegacyTelemetry(request);
        if (request.sensorType() == null && request.capabilityKey() == null)
            throw invalidOnboardingTelemetry(device, "CAPABILITY_IDENTITY_MISSING", "sensorType or capabilityKey is required");
        String identity=request.capabilityKey()!=null?request.capabilityKey():request.sensorType();
        com.nexus.device.binding.runtime.ResolvedDeviceCapability resolved;
        try {
            resolved=capabilityResolver.resolve(request.deviceId(),identity,CapabilityDirection.READ);
            if(request.sensorType()!=null&&request.capabilityKey()!=null){var legacy=capabilityResolver.resolve(request.deviceId(),request.sensorType(),CapabilityDirection.READ);if(!legacy.definition().getId().equals(resolved.definition().getId()))throw new IllegalArgumentException("sensorType and capabilityKey conflict");}
        } catch (IllegalArgumentException | ResourceNotFoundException exception) {
            throw invalidOnboardingTelemetry(device, "CAPABILITY_IDENTITY_INVALID", exception.getMessage());
        }
        String unit=request.unit()!=null?request.unit():resolved.effectiveUnit();
        var validation=capabilityValueValidator.validate(resolved.definition(),request.value(),unit,CapabilityOperation.TELEMETRY,null);
        if(!validation.isValid())
            throw invalidOnboardingTelemetry(device, validation.errors().getFirst().code().name(),
                    validation.errors().getFirst().message());
        if (bindingConstraints != null && resolved.binding() != null) {
            try { bindingConstraints.validateValue(resolved.definition(), resolved.binding().getConfiguration(), request.value()); }
            catch (IllegalArgumentException exception) {
                throw invalidOnboardingTelemetry(device, "BINDING_CONSTRAINT_INVALID", exception.getMessage());
            }
        }
        NormalizedTelemetry normalized=new NormalizedTelemetry(request.deviceId(),request.timestamp(),resolved.definition().getKey(),identity,request.value(),unit,resolved);
        String correlationId = UUID.randomUUID().toString();
        long stageStart = System.currentTimeMillis();

        TelemetryRecord record = new TelemetryRecord(
                UUID.randomUUID(),
                request.deviceId(),
                request.timestamp(),
                request.sensorType()!=null?request.sensorType():resolved.definition().getKey(), normalized.value(), normalized.unit(),
                resolved.definition().getId(),resolved.binding()==null?null:resolved.binding().getId(),resolved.definition().getKey(),resolved.definition().getValueType(),resolved.resolutionSource()
        );

        telemetryRepository.save(record);

        // Broadcast telemetry in real time
        realtimePublisher.publishTelemetry(record, correlationId);

        // Pipeline trace for F7.5 visualization
        pipelineService.recordTrace(
                PipelineTrace.of(correlationId, request.deviceId().toString(), PipelineStage.TELEMETRY, stageStart, "SUCCESS",
                        request, java.util.Map.of("sensorType", record.getSensorType(), "value", request.value(),"resolutionSource",resolved.resolutionSource().name())));

        // Update Digital Twin
        digitalTwinService.updateTwin(normalized, correlationId);

        if (onboarding != null && device.getStatus() != DeviceStatus.ACTIVE)
            onboarding.completeFirstTelemetry(device.getId());

        return TelemetryResponse.from(record);
    }

    private IllegalArgumentException invalidOnboardingTelemetry(Device device, String code, String message) {
        if (onboarding == null || device.getStatus() == DeviceStatus.ACTIVE)
            return new IllegalArgumentException(message);
        onboarding.rejectInvalidTelemetry(device.getId(), code, message);
        return new OnboardingTelemetryRejectedException(message);
    }

    private TelemetryResponse processLegacyTelemetry(TelemetryRequest request) {
        if (!(request.value() instanceof Number number)) throw new IllegalArgumentException("Legacy telemetry value must be numeric");
        String correlationId=UUID.randomUUID().toString(); long stageStart=System.currentTimeMillis();
        var record=new TelemetryRecord(UUID.randomUUID(),request.deviceId(),request.timestamp(),request.sensorType(),number.doubleValue(),request.unit());
        telemetryRepository.save(record); realtimePublisher.publishTelemetry(record,correlationId);
        pipelineService.recordTrace(PipelineTrace.of(correlationId,request.deviceId().toString(),PipelineStage.TELEMETRY,stageStart,"SUCCESS",request,java.util.Map.of("sensorType",request.sensorType(),"value",request.value())));
        digitalTwinService.updateTwin(request,correlationId); return TelemetryResponse.from(record);
    }

    public List<TelemetryResponse> getLatestTelemetry(int limit) {
        if (limit <= 0) {
            limit = 100;
        }
        return telemetryRepository.findLatest(limit).stream()
                .map(TelemetryResponse::from)
                .collect(Collectors.toList());
    }
}
