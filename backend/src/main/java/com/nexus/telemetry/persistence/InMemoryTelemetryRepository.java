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
    @Override
    public List<TelemetryRecord> query(com.nexus.telemetry.domain.TelemetryQuery query) {
        java.util.stream.Stream<TelemetryRecord> stream = queue.stream()
                .filter(r -> r.getDeviceId().equals(query.deviceId()));

        if (query.after() != null) {
            stream = stream.filter(r -> r.getTimestamp().isAfter(query.after()));
        }
        if (query.before() != null) {
            stream = stream.filter(r -> r.getTimestamp().isBefore(query.before()));
        }

        java.util.Comparator<TelemetryRecord> comparator = java.util.Comparator.comparing(TelemetryRecord::getTimestamp);
        if ("desc".equalsIgnoreCase(query.sortDirection())) {
            comparator = comparator.reversed();
        }

        return stream.sorted(comparator)
                .limit(query.limit())
                .collect(Collectors.toList());
    }
}
