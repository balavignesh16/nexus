package com.nexus.telemetry.api;

import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.application.TelemetryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TelemetryResponse ingestTelemetry(@Valid @RequestBody TelemetryRequest request) {
        return telemetryService.ingestTelemetry(request);
    }

    @GetMapping
    public List<TelemetryResponse> getLatestTelemetry(
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        return telemetryService.getLatestTelemetry(limit);
    }
}
