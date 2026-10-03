package com.nexus.twin.api;

import com.nexus.twin.api.dto.DigitalTwinResponse;
import com.nexus.twin.application.DigitalTwinService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class DigitalTwinController {

    private final DigitalTwinService twinService;

    public DigitalTwinController(DigitalTwinService twinService) {
        this.twinService = twinService;
    }

    @GetMapping("/devices/{deviceId}/twin")
    public ResponseEntity<DigitalTwinResponse> getTwin(@PathVariable UUID deviceId) {
        List<com.nexus.twin.domain.DigitalTwin> states = twinService.getTwinCapabilities(deviceId);
        if (states.isEmpty()) {
            throw new com.nexus.shared.exception.ResourceNotFoundException("DigitalTwin", deviceId.toString());
        }
        return ResponseEntity.ok(DigitalTwinResponse.from(states));
    }

    @GetMapping("/twins")
    public ResponseEntity<List<DigitalTwinResponse>> getAllTwins(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        
        List<DigitalTwinResponse> twins = twinService.getAllTwins().stream()
                .limit(limit)
                .map(twin -> {
                    List<com.nexus.twin.domain.DigitalTwin> states = twinService.getTwinCapabilities(twin.deviceId());
                    // A concurrent device removal must not turn a list request
                    // into a 500 after this representative snapshot was read.
                    return DigitalTwinResponse.from(states.isEmpty() ? List.of(twin) : states);
                })
                .collect(Collectors.toList());
                
        return ResponseEntity.ok(twins);
    }
}
