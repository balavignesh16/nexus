package com.nexus.telemetry.domain;

import java.util.List;

public interface TelemetryRepositoryCustom {
    List<TelemetryRecord> findLatest(int limit);
    List<TelemetryRecord> query(TelemetryQuery query);
}
