package com.nexus.telemetry.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TelemetryRepository extends JpaRepository<TelemetryRecord, TelemetryRecordId>, TelemetryRepositoryCustom {
}
