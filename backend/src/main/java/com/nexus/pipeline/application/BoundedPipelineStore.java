package com.nexus.pipeline.application;

import com.nexus.pipeline.domain.PipelineTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Component
public class BoundedPipelineStore {
    private static final int MAX_SIZE = 10_000;
    
    // Deque allows us to add at the head/tail and remove from the other end efficiently.
    // Recent traces will be at the front (or we can just add to back and remove from front).
    private final ConcurrentLinkedDeque<PipelineTrace> store = new ConcurrentLinkedDeque<>();

    public void add(PipelineTrace trace) {
        store.addLast(trace);
        if (store.size() > MAX_SIZE) {
            store.pollFirst();
        }
    }

    public List<PipelineTrace> getRecentTraces(int limit) {
        // Collect from the end (most recent) backwards
        List<PipelineTrace> allTraces = new ArrayList<>(store);
        int fromIndex = Math.max(0, allTraces.size() - limit);
        return allTraces.subList(fromIndex, allTraces.size());
    }

    public List<PipelineTrace> getTracesByCorrelationId(String correlationId) {
        return store.stream()
                .filter(trace -> correlationId.equals(trace.correlationId()))
                .collect(Collectors.toList());
    }
    
    public int size() {
        return store.size();
    }
}
