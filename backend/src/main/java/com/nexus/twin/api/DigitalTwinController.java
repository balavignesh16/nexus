package com.nexus.twin.api;

import com.nexus.twin.api.dto.DigitalTwinResponse;
import com.nexus.twin.application.DigitalTwinService;
import com.nexus.twin.domain.DesiredTwinState;
import com.nexus.twin.domain.DesiredTwinStateRepository;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class DigitalTwinController {

    private final DigitalTwinService twinService;
    private final DesiredTwinStateRepository desiredRepository;
    private final ActionHistoryRepository actionRepository;
    private final TwinStateQualityEvaluator qualityEvaluator;

    public DigitalTwinController(DigitalTwinService twinService) {
        this(twinService, null, null,null);
    }

    public DigitalTwinController(DigitalTwinService twinService, DesiredTwinStateRepository desiredRepository,
            ActionHistoryRepository actionRepository) {this(twinService,desiredRepository,actionRepository,null);}

    @Autowired
    public DigitalTwinController(DigitalTwinService twinService, DesiredTwinStateRepository desiredRepository,
            ActionHistoryRepository actionRepository,TwinStateQualityEvaluator qualityEvaluator) {
        this.twinService = twinService;
        this.desiredRepository = desiredRepository;
        this.actionRepository = actionRepository;
        this.qualityEvaluator=qualityEvaluator;
    }

    @GetMapping("/devices/{deviceId}/twin")
    public ResponseEntity<DigitalTwinResponse> getTwin(@PathVariable UUID deviceId) {
        List<com.nexus.twin.domain.DigitalTwin> states = twinService.getTwinCapabilities(deviceId);
        List<DesiredTwinState> desired = desiredRepository == null ? List.of() : desiredRepository.findByDeviceId(deviceId);
        if (states.isEmpty() && desired.isEmpty()) {
            throw new com.nexus.shared.exception.ResourceNotFoundException("DigitalTwin", deviceId.toString());
        }
        return ResponseEntity.ok(DigitalTwinResponse.from(states, desired, actionsFor(desired),qualities(states,desired)));
    }

    @GetMapping("/twins")
    public ResponseEntity<List<DigitalTwinResponse>> getAllTwins(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        
        java.util.Map<UUID, com.nexus.twin.domain.DigitalTwin> reportedDevices = new java.util.LinkedHashMap<>();
        for (var twin : twinService.getAllTwins()) reportedDevices.put(twin.deviceId(), twin);
        java.util.Map<UUID, List<DesiredTwinState>> desiredByDevice = new java.util.LinkedHashMap<>();
        if (desiredRepository != null) for (DesiredTwinState state : desiredRepository.findAll())
            desiredByDevice.computeIfAbsent(state.getDeviceId(), ignored -> new java.util.ArrayList<>()).add(state);
        java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>(reportedDevices.keySet());
        ids.addAll(desiredByDevice.keySet());
        List<DigitalTwinResponse> twins = ids.stream().limit(limit).map(id -> {
            var states = twinService.getTwinCapabilities(id);
            if (states.isEmpty() && reportedDevices.containsKey(id)) states = List.of(reportedDevices.get(id));
            var desired = desiredByDevice.getOrDefault(id, List.of());
            return DigitalTwinResponse.from(states, desired, actionsFor(desired),qualities(states,desired));
        }).collect(Collectors.toList());
                
        return ResponseEntity.ok(twins);
    }

    private java.util.Map<UUID, ActionHistoryRecord> actionsFor(List<DesiredTwinState> desired) {
        if (actionRepository == null || desired.isEmpty()) return java.util.Map.of();
        java.util.Map<UUID, ActionHistoryRecord> result = new java.util.HashMap<>();
        for (var action : actionRepository.findAllById(desired.stream().map(DesiredTwinState::getCommandId).toList()))
            result.put(action.getRequestId(), action);
        return result;
    }

    private java.util.Map<String,com.nexus.twin.domain.TwinStateQualityEvidence> qualities(List<com.nexus.twin.domain.DigitalTwin> states,List<DesiredTwinState> desired){
        if(qualityEvaluator==null)return java.util.Map.of();
        java.util.Map<String,com.nexus.twin.domain.TwinStateQualityEvidence> result=new java.util.HashMap<>();
        for(var state:states)result.put(state.stateKey(),qualityEvaluator.evaluate(state));
        for(var intent:desired){
            boolean observed=states.stream().anyMatch(state -> intent.getBindingId()!=null&&intent.getBindingId().equals(state.bindingId())
                    ||intent.getLegacyCapabilityType()!=null&&intent.getLegacyCapabilityType().equals(state.legacyCapabilityType()));
            if(!observed){
                String id=intent.getTargetKey()+(intent.getSubtargetKey().isEmpty()?"":"/"+intent.getSubtargetKey());
                result.put(id,qualityEvaluator.evaluateMissing(intent.getDeviceId(),intent.getBindingId()));
            }
        }
        return result;
    }
}
