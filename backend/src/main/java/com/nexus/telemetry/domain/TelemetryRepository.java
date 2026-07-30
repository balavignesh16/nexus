package com.nexus.telemetry.domain;

import java.util.List;

public interface TelemetryRepository {
    void save(TelemetryRecord record);
    List<TelemetryRecord> findLatest(int limit);
}
