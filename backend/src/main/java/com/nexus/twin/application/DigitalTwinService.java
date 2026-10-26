package com.nexus.twin.application;

import com.nexus.device.domain.DeviceRepository;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.NormalizedTelemetry;
import com.nexus.twin.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DigitalTwinService {
    private static final Logger log=LoggerFactory.getLogger(DigitalTwinService.class);

    private final DigitalTwinRegistry registry;
    private final ApplicationEventPublisher events;
    private final RealtimePublisher realtime;
    private final PipelineService pipeline;
    private final DigitalTwinRepository repository;
    private final TwinStateTransitionRepository transitions;
    private final DeviceRepository devices;
    private final Clock clock;
    @Value("${nexus.twin.maximum-future-skew:PT5M}")
    private Duration maximumFutureSkew=Duration.ofMinutes(5);
    private final ConcurrentHashMap<UUID,Object> deviceLocks=new ConcurrentHashMap<>();

    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    @Autowired
    void setRuleFacts(com.nexus.rule.trigger.RuleSourceFacts ruleFacts){this.ruleFacts=ruleFacts;}

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;

    public record Mutation(DigitalTwin previous, DigitalTwin current, String correlationId,
            long stageStart, boolean updated, boolean futureDated) {}

    public DigitalTwinService(DigitalTwinRegistry registry, ApplicationEventPublisher events,
            RealtimePublisher realtime, PipelineService pipeline) {
        this(registry,events,realtime,pipeline,null,null,null,Clock.systemUTC());
    }

    @Autowired
    public DigitalTwinService(DigitalTwinRegistry registry, ApplicationEventPublisher events,
            RealtimePublisher realtime, PipelineService pipeline, DigitalTwinRepository repository,
            TwinStateTransitionRepository transitions, DeviceRepository devices, Clock clock) {
        this.registry=registry; this.events=events; this.realtime=realtime; this.pipeline=pipeline;
        this.repository=repository; this.transitions=transitions; this.devices=devices; this.clock=clock;
    }

    @Transactional
    public void updateTwin(TelemetryRequest request,String correlationId) {
        String identity=request.capabilityKey()!=null?request.capabilityKey():request.sensorType();
        Mutation result=prepareUpdate(new NormalizedTelemetry(request.deviceId(),request.timestamp(),identity,
                request.sensorType(),request.value(),request.unit(),null),correlationId,clock.instant());
        afterCommit(() -> publishAfterCommit(result));
    }

    @Transactional
    public void updateTwin(NormalizedTelemetry request,String correlationId) {
        Mutation mutation=prepareUpdate(request,correlationId,clock.instant());
        afterCommit(() -> publishAfterCommit(mutation));
    }

    /**
     * Runs {@code work} holding this device's in-process lock and, inside the caller's transaction, the device row lock, acquired in the SAME order
     * {@link #prepareUpdate} uses (in-process lock first, then {@code SELECT ... FOR NO KEY UPDATE}). The in-process lock is released when {@code work}
     * returns; the row lock stays until the transaction ends. P4.5/P4.6 (F3): identified telemetry takes both BEFORE its dedupe claim, whose foreign key
     * takes a key-share lock on the device row. Asking for the stronger row lock only after the claim formed a cycle with a device deletion (which holds
     * the row lock and waits for the key-share holder). Taking the row lock first without the in-process lock would instead let an identified and an
     * unidentified sample of one device deadlock through the monitor, a cycle the database cannot see, so both are taken here in the established
     * order. {@link #prepareUpdate} re-enters both: the monitor is reentrant and the row lock is already held. Must be called inside a transaction.
     */
    public <T> T underDeviceLock(UUID deviceId, java.util.function.Supplier<T> work) {
        synchronized(deviceLocks.computeIfAbsent(deviceId,ignored->new Object())) {
            if (devices!=null) devices.findByIdForUpdate(deviceId).orElseThrow(()->new com.nexus.shared.exception.ResourceNotFoundException("Device",deviceId.toString()));
            return work.get();
        }
    }

    public Mutation prepareUpdate(NormalizedTelemetry request,String correlationId,Instant receivedAt) {
        long stageStart=System.currentTimeMillis();
        String stateKey=stateKey(request);
        // PostgreSQL persists timestamp(6). Compare at that precision so an
        // exact replay cannot appear newer solely due to truncated nanoseconds.
        Instant eventTime=request.timestamp().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (eventTime.isAfter(receivedAt.plus(maximumFutureSkew))) {
            log.warn("Ignoring future-dated Twin candidate device={} stateKey={} eventTime={} receivedAt={}",
                    request.deviceId(),stateKey,request.timestamp(),receivedAt);
            return new Mutation(null,null,correlationId,stageStart,false,true);
        }
        synchronized(deviceLocks.computeIfAbsent(request.deviceId(),ignored->new Object())) {
        if (devices!=null) devices.findByIdForUpdate(request.deviceId()).orElseThrow();
        Optional<DigitalTwin> existing=repository!=null
                ? repository.findByDeviceIdAndStateKey(request.deviceId(),stateKey)
                : registry.get(request.deviceId(),request.externalIdentifier());
        if(existing.isPresent()&&!eventTime.isAfter(existing.get().latestTimestamp())) {
            log.debug("Ignored replay/out-of-order telemetry device={} stateKey={}",request.deviceId(),stateKey);
            return new Mutation(existing.get(),null,correlationId,stageStart,false,false);
        }
        Instant now=clock.instant();
        String apiKey=configuredApiKey(request);
        var resolved=request.resolved();
        DigitalTwin next=resolved==null
                ? new DigitalTwin(request.deviceId(),request.timestamp(),apiKey,request.value() instanceof Number n?n.doubleValue():null,
                    request.unit(),now,Map.of())
                : new DigitalTwin(request.deviceId(),request.timestamp(),apiKey,request.externalIdentifier(),request.value(),request.unit(),
                    now,receivedAt,Map.of(),resolved.definition().getId(),resolved.binding()==null?null:resolved.binding().getId(),
                    request.capabilityKey(),resolved.definition().getValueType(),resolved.resolutionSource());
        DigitalTwin previous=existing.orElse(null);
        if(repository!=null) {
            // saveAndFlush merges `next` onto the managed row. Without detaching it first, `previous` would silently
            // become the new state and every update after creation would look like "no semantic change".
            if(previous!=null&&entities!=null)entities.detach(previous);
            repository.saveAndFlush(next);
            if(meaningfullyChanged(previous,next)) {
                var transition=transitions.save(TwinStateTransition.reported(previous,next,correlationId,now));
                if(ruleFacts!=null && transition!=null) ruleFacts.twinStateChanged(transition,next);
            }
        } else registry.put(next);
        return new Mutation(previous,next,correlationId,stageStart,true,false);
        }
    }

    public void publishAfterCommit(Mutation mutation) {
        if(mutation==null||!mutation.updated()||mutation.current()==null)return;
        projectAfterCommit(mutation);
        publishAfterProjection(mutation);
    }

    public void projectAfterCommit(Mutation mutation) {
        if(mutation==null||!mutation.updated()||mutation.current()==null)return;
        DigitalTwin next=mutation.current();
        if(repository!=null) {
            try { registry.project(next); }
            catch(RuntimeException projectionFailure) {
                registry.evict(next.deviceId(),next.stateKey());
                log.error("Twin cache projection failed; evicted device={} stateKey={}",next.deviceId(),next.stateKey(),projectionFailure);
            }
        }
    }

    public void publishAfterProjection(Mutation mutation) {
        if(mutation==null||!mutation.updated()||mutation.current()==null)return;
        DigitalTwin next=mutation.current();
        try {
            realtime.publishTwinDelta(mutation.previous(),next,mutation.correlationId());
            pipeline.recordTrace(PipelineTrace.of(mutation.correlationId(),next.deviceId().toString(), PipelineStage.TWIN,
                    mutation.stageStart(),"SUCCESS",next,Map.of("sensorType",next.latestSensorType(),"value",next.typedValue())));
            events.publishEvent(new TwinUpdatedEvent(next.deviceId(),clock.instant(),mutation.previous(),next,mutation.correlationId()));
        } catch(RuntimeException failure) {
            log.error("Post-commit Twin publication failed device={} stateKey={}",next.deviceId(),next.stateKey(),failure);
        }
    }

    private static boolean meaningfullyChanged(DigitalTwin previous,DigitalTwin next) {
        if(previous==null)return true;
        return previous.valueType()!=next.valueType()
                ||!Objects.equals(normalize(previous.typedValue()),normalize(next.typedValue()))
                ||!Objects.equals(previous.latestUnit(),next.latestUnit())
                ||!Objects.equals(previous.metadata(),next.metadata());
    }
    private static Object normalize(Object value){
        if(value instanceof Number n)return new java.math.BigDecimal(n.toString()).stripTrailingZeros();
        return value;
    }
    private static String stateKey(NormalizedTelemetry request) {
        if(request.resolved()!=null&&request.resolved().binding()!=null)return "binding:"+request.resolved().binding().getId();
        String key=request.externalIdentifier().toUpperCase(Locale.ROOT);
        if(key.endsWith("_SENSOR"))key=key.substring(0,key.length()-7);
        return "legacy:"+key;
    }
    private static String configuredApiKey(NormalizedTelemetry request) {
        if(request.resolved()!=null&&request.resolved().binding()!=null) {
            Object configured=request.resolved().binding().getExternalIdentifiers().get("telemetryKey");
            if(configured!=null&&!configured.toString().isBlank())return configured.toString();
        }
        return request.capabilityKey()!=null?request.capabilityKey():request.externalIdentifier();
    }

    private static void afterCommit(Runnable work) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { work.run(); }
            });
        } else work.run();
    }

    public Optional<DigitalTwin> getTwin(UUID id){return registry.get(id);}
    public List<DigitalTwin> getTwinCapabilities(UUID id){return registry.getAllForDevice(id);}
    public List<DigitalTwin> getAllTwins(){return registry.listAll();}
}
