package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.application.DigitalTwinService;
import com.nexus.twin.application.VerificationService;
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
import com.nexus.telemetry.domain.TelemetryRecordId;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TelemetryService {
    private static final Logger log=LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryRepository telemetryRepository;
    private final DeviceRepository deviceRepository;
    private final DigitalTwinService digitalTwinService;
    private final RealtimePublisher realtimePublisher;
    private final PipelineService pipelineService;
    private final RuntimeCapabilityResolver capabilityResolver;
    private final CapabilityValueValidator capabilityValueValidator;
    private final OnboardingLifecycleService onboarding;
    private final BindingConstraintService bindingConstraints;
    private final Clock clock;
    private VerificationService verification;

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
                capabilityResolver, capabilityValueValidator, null, null, Clock.systemUTC());
    }

    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository,
                            DigitalTwinService digitalTwinService, RealtimePublisher realtimePublisher,
                            PipelineService pipelineService, RuntimeCapabilityResolver capabilityResolver,
                            CapabilityValueValidator capabilityValueValidator,
                            OnboardingLifecycleService onboarding, BindingConstraintService bindingConstraints) {
        this(telemetryRepository,deviceRepository,digitalTwinService,realtimePublisher,pipelineService,
                capabilityResolver,capabilityValueValidator,onboarding,bindingConstraints,Clock.systemUTC());
    }

    @Autowired
    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository,
                            DigitalTwinService digitalTwinService, RealtimePublisher realtimePublisher,
                            PipelineService pipelineService, RuntimeCapabilityResolver capabilityResolver,
                            CapabilityValueValidator capabilityValueValidator,
                            OnboardingLifecycleService onboarding, BindingConstraintService bindingConstraints,
                            Clock clock) {
        this.telemetryRepository = telemetryRepository;
        this.deviceRepository = deviceRepository;
        this.digitalTwinService = digitalTwinService;
        this.realtimePublisher = realtimePublisher;
        this.pipelineService = pipelineService;
        this.capabilityResolver = capabilityResolver;
        this.capabilityValueValidator = capabilityValueValidator;
        this.onboarding = onboarding;
        this.bindingConstraints = bindingConstraints;
        this.clock=clock;
    }

    @Autowired
    void setVerification(VerificationService verification){this.verification=verification;}
    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    @Autowired
    void setRuleFacts(com.nexus.rule.trigger.RuleSourceFacts ruleFacts){this.ruleFacts=ruleFacts;}
    private InboundMessageDedupe dedupe;
    @Autowired
    void setDedupe(InboundMessageDedupe dedupe){this.dedupe=dedupe;}
    private MeterRegistry meters;
    @Autowired(required = false)
    void setMeters(MeterRegistry meters){this.meters=meters;}

    /** Entry point without an explicit transport: the HTTP semantics (the transport is a diagnostic label only, R17). */
    @Transactional
    public TelemetryResponse processTelemetry(TelemetryRequest request) {
        return processTelemetry(request, TelemetrySource.HTTP);
    }

    @Transactional
    public TelemetryResponse processTelemetry(TelemetryRequest request, TelemetrySource source) {
        Instant receivedAt=clock.instant();
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("Device", request.deviceId().toString()));

        if (onboarding == null ? device.getStatus() != DeviceStatus.ACTIVE : !onboarding.admitsTelemetry(device)) {
            throw new IllegalStateException("Cannot ingest telemetry for inactive device");
        }

        // R17: an identified message is ingested under the device's locks, taken BEFORE its dedupe claim and in the order DigitalTwinService.prepareUpdate
        // uses (in-process lock, then the device row lock). The claim's foreign key takes a key-share lock on the device row; if the stronger row lock
        // were requested only afterwards, a device deletion holding that row lock (and waiting for the key-share holder) would form a database-detected
        // deadlock (follow-up F3, SQLSTATE 40P01). A message without an id keeps the previous path: no claim, no early lock.
        if (request.messageId() == null || dedupe == null)
            return ingest(request, receivedAt, device, null);
        return digitalTwinService.underDeviceLock(request.deviceId(), () -> ingest(request, receivedAt, device, source));
    }

    private TelemetryResponse ingest(TelemetryRequest request, Instant receivedAt, Device device, TelemetrySource claimSource) {
        // R17 insert-first: the messageId claim comes before every side effect and shares this transaction, so a later failure
        // removes the claim and a concurrent identical submission waits for this transaction and then sees a duplicate.
        UUID recordId = UUID.randomUUID();
        if (claimSource != null) {
            var outcome = dedupe.claim(request.deviceId(), request.messageId(), claimSource, TelemetryFingerprint.of(request), recordId);
            if (outcome instanceof InboundMessageDedupe.Conflict) {
                count("nexus.telemetry.dedupe.conflicts", claimSource);
                log.warn("Conflicting duplicate telemetry messageId for device {} via {}: same id, different payload; the first submission wins, nothing is stored.", request.deviceId(), claimSource);
                throw new TelemetryMessageConflictException(request.deviceId(), request.messageId());
            }
            if (outcome instanceof InboundMessageDedupe.Duplicate duplicate) {
                count("nexus.telemetry.duplicates", claimSource);
                log.info("Duplicate telemetry messageId for device {} via {}: already accepted, no new side effects.", request.deviceId(), claimSource);
                return duplicateResponse(request, duplicate.originalRecordId());
            }
        }

        if (capabilityResolver == null) return processLegacyTelemetry(request, recordId);
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
                recordId,
                request.deviceId(),
                request.timestamp(),
                request.sensorType()!=null?request.sensorType():resolved.definition().getKey(), normalized.value(), normalized.unit(),
                resolved.definition().getId(),resolved.binding()==null?null:resolved.binding().getId(),resolved.definition().getKey(),resolved.definition().getValueType(),resolved.resolutionSource()
        );

        telemetryRepository.save(record);
        DigitalTwinService.Mutation mutation=digitalTwinService.prepareUpdate(normalized,correlationId,receivedAt);
        if(verification!=null && mutation.updated())
            verification.observed(record,mutation.current(),request.readbackCommandId(),correlationId,receivedAt);
        afterCommit(() -> {
            digitalTwinService.projectAfterCommit(mutation);
            try {
                realtimePublisher.publishTelemetry(record,correlationId);
                pipelineService.recordTrace(PipelineTrace.of(correlationId,request.deviceId().toString(),PipelineStage.TELEMETRY,
                        stageStart,"SUCCESS",request,java.util.Map.of("sensorType",record.getSensorType(),"value",request.value(),
                        "resolutionSource",resolved.resolutionSource().name())));
            } catch(RuntimeException failure) {
                log.error("Post-commit telemetry publication failed device={}",request.deviceId(),failure);
            }
            digitalTwinService.publishAfterProjection(mutation);
        });
        // Same source transaction: the accepted-observation fact commits with the Twin mutation, then routes.
        if(ruleFacts!=null && mutation.updated())
            ruleFacts.acceptedTelemetry(record.getId(),mutation.current(),request.timestamp(),correlationId);

        if (onboarding != null && device.getStatus() != DeviceStatus.ACTIVE)
            onboarding.completeFirstTelemetry(device.getId());

        return TelemetryResponse.from(record);
    }

    private void count(String name, TelemetrySource source) {
        if (meters != null) meters.counter(name, "source", source.name()).increment();
    }

    /** A duplicate repeats an accepted sample: report the original when it is still stored, otherwise echo the request without an id. */
    private TelemetryResponse duplicateResponse(TelemetryRequest request, UUID originalRecordId) {
        if (originalRecordId != null) {
            var original = telemetryRepository.findById(new TelemetryRecordId(originalRecordId, request.timestamp()));
            if (original.isPresent()) return TelemetryResponse.from(original.get()).asDuplicate();
        }
        Double numeric = request.value() instanceof Number number ? number.doubleValue() : null;
        return new TelemetryResponse(null, request.deviceId(), request.timestamp(), request.sensorType(), numeric, request.unit(),
                request.capabilityKey(), null, request.value()).asDuplicate();
    }

    private IllegalArgumentException invalidOnboardingTelemetry(Device device, String code, String message) {
        if (onboarding == null || device.getStatus() == DeviceStatus.ACTIVE)
            return new InvalidTelemetryException(code, message);
        onboarding.rejectInvalidTelemetry(device.getId(), code, message);
        return new OnboardingTelemetryRejectedException(message);
    }

    private TelemetryResponse processLegacyTelemetry(TelemetryRequest request, UUID recordId) {
        if (!(request.value() instanceof Number number)) throw new InvalidTelemetryException("LEGACY_VALUE_NOT_NUMERIC", "Legacy telemetry value must be numeric");
        String correlationId=UUID.randomUUID().toString(); long stageStart=System.currentTimeMillis();
        var record=new TelemetryRecord(recordId,request.deviceId(),request.timestamp(),request.sensorType(),number.doubleValue(),request.unit());
        telemetryRepository.save(record);
        afterCommit(()->{realtimePublisher.publishTelemetry(record,correlationId);
            pipelineService.recordTrace(PipelineTrace.of(correlationId,request.deviceId().toString(),PipelineStage.TELEMETRY,stageStart,"SUCCESS",request,java.util.Map.of("sensorType",request.sensorType(),"value",request.value())));
            digitalTwinService.updateTwin(request,correlationId);});
        return TelemetryResponse.from(record);
    }

    private static void afterCommit(Runnable work) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){work.run();}
            });
        } else work.run();
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
