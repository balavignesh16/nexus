package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.telemetry.api.dto.TelemetryQueryResponse;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryQuery;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TelemetryQueryService {

    private final TelemetryRepository telemetryRepository;
    private final DeviceRepository deviceRepository;

    public TelemetryQueryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository) {
        this.telemetryRepository = telemetryRepository;
        this.deviceRepository = deviceRepository;
    }

    public TelemetryQueryResponse queryTelemetry(TelemetryQuery query) {
        validateDevice(query.deviceId());

        // Ask for 1 more than limit to determine if there are more records
        int fetchLimit = query.limit() + 1;
        TelemetryQuery internalQuery = new TelemetryQuery(
                query.deviceId(),
                query.before(),
                query.after(),
                fetchLimit,
                query.sortDirection()
        );

        List<TelemetryRecord> records = telemetryRepository.query(internalQuery);
        
        boolean hasMore = records.size() > query.limit();
        List<TelemetryResponse> responseRecords = records.stream()
                .limit(query.limit())
                .map(TelemetryResponse::from)
                .collect(Collectors.toList());

        return new TelemetryQueryResponse(responseRecords, responseRecords.size(), hasMore);
    }

    public Optional<TelemetryResponse> getLatestTelemetry(UUID deviceId) {
        validateDevice(deviceId);
        
        TelemetryQuery query = new TelemetryQuery(deviceId, null, null, 1, "desc");
        List<TelemetryRecord> records = telemetryRepository.query(query);
        
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(TelemetryResponse.from(records.get(0)));
    }

    private void validateDevice(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("Device", deviceId.toString()));

        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Cannot query telemetry for inactive device");
        }
    }
}
