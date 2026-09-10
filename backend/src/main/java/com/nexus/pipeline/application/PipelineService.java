package com.nexus.pipeline.application;

import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineTraceRepository;
import com.nexus.realtime.RealtimePublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class PipelineService {

    private final PipelineTraceRepository repository;
    private final RealtimePublisher realtimePublisher;

    public PipelineService(PipelineTraceRepository repository, RealtimePublisher realtimePublisher) {
        this.repository = repository;
        this.realtimePublisher = realtimePublisher;
    }

    /**
     * Record a pipeline trace. Saves to the database and broadcasts via RealtimePublisher.
     */
    public void recordTrace(PipelineTrace trace) {
        repository.save(trace);
        realtimePublisher.publishPipelineTrace(trace);
    }

    public List<PipelineTrace> getHistory(int limit) {
        return repository.findAllByOrderByStartTimestampDesc(PageRequest.of(0, limit)).getContent();
    }

    public List<PipelineTrace> getByCorrelationId(String correlationId) {
        return repository.findByCorrelationIdOrderByStartTimestampAsc(correlationId);
    }

    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        long totalStored = repository.count();
        metrics.put("totalStored", totalStored);
        
        List<PipelineTrace> recent = repository.findAllByOrderByStartTimestampDesc(PageRequest.of(0, 1000)).getContent();
        long errorCount = recent.stream().filter(t -> "ERROR".equals(t.status()) || "FAILURE".equals(t.status())).count();
        metrics.put("recentErrorCount", errorCount);
        
        double avgLatency = recent.stream().mapToLong(PipelineTrace::getDurationMs).average().orElse(0.0);
        metrics.put("averageLatencyMs", avgLatency);
        
        return metrics;
    }
}
