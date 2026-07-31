package com.nexus.telemetry.api;

import com.nexus.telemetry.api.dto.TelemetryQueryResponse;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.application.TelemetryQueryService;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.domain.TelemetryQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TelemetryController {

    private final TelemetryService telemetryService;
    private final TelemetryQueryService telemetryQueryService;

    public TelemetryController(TelemetryService telemetryService, TelemetryQueryService telemetryQueryService) {
        this.telemetryService = telemetryService;
        this.telemetryQueryService = telemetryQueryService;
    }

    @PostMapping("/telemetry")
    @ResponseStatus(HttpStatus.CREATED)
    public TelemetryResponse ingestTelemetry(@jakarta.validation.Valid @RequestBody TelemetryRequest request) {
        return telemetryService.ingestTelemetry(request);
    }

    @GetMapping("/telemetry")
    public List<TelemetryResponse> getLatestTelemetry(@RequestParam(defaultValue = "100") int limit) {
        return telemetryService.getLatestTelemetry(limit);
    }

    @GetMapping("/devices/{deviceId}/telemetry")
    public TelemetryQueryResponse getDeviceTelemetry(
            @PathVariable UUID deviceId,
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) Instant after,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "desc") String sort
    ) {
        TelemetryQuery query = new TelemetryQuery(deviceId, before, after, limit, sort);
        return telemetryQueryService.queryTelemetry(query);
    }

    @GetMapping("/devices/{deviceId}/telemetry/latest")
    public ResponseEntity<TelemetryResponse> getLatestDeviceTelemetry(@PathVariable UUID deviceId) {
        return telemetryQueryService.getLatestTelemetry(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
