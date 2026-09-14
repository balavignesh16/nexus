package com.nexus.pipeline.api;

import com.nexus.pipeline.api.dto.PipelineTraceResponse;
import com.nexus.pipeline.application.PipelineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipeline")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * Optional {@code deviceId} filter (Phase 3): real, persisted
     * PipelineTrace.deviceId column - never every trace in the system.
     * Returns a purpose-built DTO, not the raw JPA entity.
     */
    @GetMapping("/history")
    public List<PipelineTraceResponse> getHistory(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        if (deviceId != null && deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId, if supplied, must not be blank");
        }
        var traces = deviceId != null
                ? pipelineService.getHistoryForDevice(deviceId, limit)
                : pipelineService.getHistory(limit);
        return traces.stream().map(PipelineTraceResponse::from).toList();
    }

    @GetMapping("/{correlationId}")
    public List<PipelineTraceResponse> getByCorrelationId(@PathVariable String correlationId) {
        return pipelineService.getByCorrelationId(correlationId).stream().map(PipelineTraceResponse::from).toList();
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return pipelineService.getMetrics();
    }
}
