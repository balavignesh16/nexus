package com.nexus.telemetry.persistence;

import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Repository
public class InMemoryTelemetryRepository implements TelemetryRepository {

    private static final int MAX_RECORDS = 10000;
    private final ConcurrentLinkedDeque<TelemetryRecord> queue = new ConcurrentLinkedDeque<>();

    @Override
    public void save(TelemetryRecord record) {
        queue.addFirst(record);
        while (queue.size() > MAX_RECORDS) {
            queue.pollLast(); // Remove oldest
        }
    }

    @Override
    public List<TelemetryRecord> findLatest(int limit) {
        return queue.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }
}
