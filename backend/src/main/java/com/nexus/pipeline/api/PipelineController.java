package com.nexus.pipeline.api;

import com.nexus.pipeline.application.PipelineService;
import com.nexus.pipeline.domain.PipelineTrace;
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

    @GetMapping("/history")
    public List<PipelineTrace> getHistory(@RequestParam(defaultValue = "100") int limit) {
        return pipelineService.getHistory(limit);
    }

    @GetMapping("/{correlationId}")
    public List<PipelineTrace> getByCorrelationId(@PathVariable String correlationId) {
        return pipelineService.getByCorrelationId(correlationId);
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return pipelineService.getMetrics();
    }
}
