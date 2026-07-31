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
        return twinService.getTwin(deviceId)
                .map(DigitalTwinResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("DigitalTwin", deviceId.toString()));
    }

    @GetMapping("/twins")
    public ResponseEntity<List<DigitalTwinResponse>> getAllTwins(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        
        List<DigitalTwinResponse> twins = twinService.getAllTwins().stream()
                .map(DigitalTwinResponse::from)
                .limit(limit)
                .collect(Collectors.toList());
                
        return ResponseEntity.ok(twins);
    }
}
