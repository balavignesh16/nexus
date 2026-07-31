package com.nexus.telemetry.persistence;

import com.nexus.telemetry.domain.TelemetryQuery;
import com.nexus.telemetry.domain.TelemetryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTelemetryRepositoryTest {

    private InMemoryTelemetryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTelemetryRepository();
    }

    @Test
    void shouldQueryByDeviceIdAndSortCorrectly() {
        UUID deviceId = UUID.randomUUID();
        UUID otherDeviceId = UUID.randomUUID();

        repository.save(createRecord(deviceId, Instant.parse("2026-07-31T10:00:00Z")));
        repository.save(createRecord(otherDeviceId, Instant.parse("2026-07-31T10:01:00Z")));
        repository.save(createRecord(deviceId, Instant.parse("2026-07-31T10:02:00Z")));

        TelemetryQuery queryDesc = new TelemetryQuery(deviceId, null, null, 10, "desc");
        List<TelemetryRecord> descRecords = repository.query(queryDesc);
        assertThat(descRecords).hasSize(2);
        assertThat(descRecords.get(0).getTimestamp()).isEqualTo(Instant.parse("2026-07-31T10:02:00Z"));
        assertThat(descRecords.get(1).getTimestamp()).isEqualTo(Instant.parse("2026-07-31T10:00:00Z"));

        TelemetryQuery queryAsc = new TelemetryQuery(deviceId, null, null, 10, "asc");
        List<TelemetryRecord> ascRecords = repository.query(queryAsc);
        assertThat(ascRecords).hasSize(2);
        assertThat(ascRecords.get(0).getTimestamp()).isEqualTo(Instant.parse("2026-07-31T10:00:00Z"));
        assertThat(ascRecords.get(1).getTimestamp()).isEqualTo(Instant.parse("2026-07-31T10:02:00Z"));
    }

    @Test
    void shouldFilterByBeforeAndAfter() {
        UUID deviceId = UUID.randomUUID();

        repository.save(createRecord(deviceId, Instant.parse("2026-07-31T09:00:00Z")));
        repository.save(createRecord(deviceId, Instant.parse("2026-07-31T10:00:00Z")));
        repository.save(createRecord(deviceId, Instant.parse("2026-07-31T11:00:00Z")));
        repository.save(createRecord(deviceId, Instant.parse("2026-07-31T12:00:00Z")));

        TelemetryQuery query = new TelemetryQuery(
                deviceId,
                Instant.parse("2026-07-31T12:00:00Z"),
                Instant.parse("2026-07-31T09:00:00Z"),
                10,
                "asc"
        );

        List<TelemetryRecord> records = repository.query(query);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getTimestamp()).isEqualTo(Instant.parse("2026-07-31T10:00:00Z"));
        assertThat(records.get(1).getTimestamp()).isEqualTo(Instant.parse("2026-07-31T11:00:00Z"));
    }

    @Test
    void shouldLimitResults() {
        UUID deviceId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            repository.save(createRecord(deviceId, Instant.now().plusSeconds(i)));
        }

        TelemetryQuery query = new TelemetryQuery(deviceId, null, null, 2, "desc");
        List<TelemetryRecord> records = repository.query(query);

        assertThat(records).hasSize(2);
    }

    private TelemetryRecord createRecord(UUID deviceId, Instant timestamp) {
        return new TelemetryRecord(UUID.randomUUID(), deviceId, timestamp, "TEMPERATURE_SENSOR", 22.0, "CELSIUS");
    }
}
