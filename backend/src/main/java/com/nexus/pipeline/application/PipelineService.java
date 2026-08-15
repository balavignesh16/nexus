package com.nexus.pipeline.application;

import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.realtime.RealtimePublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class PipelineService {

    private final BoundedPipelineStore store;
    private final RealtimePublisher realtimePublisher;

    public PipelineService(BoundedPipelineStore store, RealtimePublisher realtimePublisher) {
        this.store = store;
        this.realtimePublisher = realtimePublisher;
    }

    /**
     * Record a pipeline trace. Saves to the bounded store and broadcasts via RealtimePublisher.
     */
    public void recordTrace(PipelineTrace trace) {
        store.add(trace);
        realtimePublisher.publishPipelineTrace(trace);
    }

    public List<PipelineTrace> getHistory(int limit) {
        return store.getRecentTraces(limit);
    }

    public List<PipelineTrace> getByCorrelationId(String correlationId) {
        return store.getTracesByCorrelationId(correlationId);
    }

    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalStored", store.size());
        
        List<PipelineTrace> recent = store.getRecentTraces(1000);
        long errorCount = recent.stream().filter(t -> "ERROR".equals(t.status()) || "FAILURE".equals(t.status())).count();
        metrics.put("recentErrorCount", errorCount);
        
        double avgLatency = recent.stream().mapToLong(PipelineTrace::durationMs).average().orElse(0.0);
        metrics.put("averageLatencyMs", avgLatency);
        
        return metrics;
    }
}
