package com.nexus.twin.application;

import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.domain.TwinUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DigitalTwinService {

    private static final Logger log = LoggerFactory.getLogger(DigitalTwinService.class);

    private final DigitalTwinRegistry registry;

    public DigitalTwinService(DigitalTwinRegistry registry) {
        this.registry = registry;
    }

    /**
     * Updates the digital twin if the incoming telemetry is strictly newer
     * than the current twin's state.
     *
     * @param request The incoming telemetry request
     */
    public void updateTwin(TelemetryRequest request) {
        Optional<DigitalTwin> currentTwinOpt = registry.get(request.deviceId());
        
        if (currentTwinOpt.isPresent()) {
            DigitalTwin currentTwin = currentTwinOpt.get();
            // Out-of-order rejection: if incoming timestamp is older than or equal to current twin, ignore it
            if (!request.timestamp().isAfter(currentTwin.latestTimestamp())) {
                log.debug("Ignored out-of-order telemetry for device {}. Current: {}, Incoming: {}",
                        request.deviceId(), currentTwin.latestTimestamp(), request.timestamp());
                return;
            }
        }

        // We assume 'lastSeen' is the time the message was processed by the backend (now)
        // while 'latestTimestamp' is the time the device reported the measurement.
        Instant now = Instant.now();
        
        DigitalTwin newTwin = new DigitalTwin(
                request.deviceId(),
                request.timestamp(),
                request.sensorType(),
                request.value(),
                request.unit(),
                now,
                Map.of() // Optional metadata empty for now
        );

        registry.put(newTwin);
        
        // Placeholder for future M6 event publishing
        TwinUpdatedEvent event = new TwinUpdatedEvent(
                request.deviceId(),
                now,
                currentTwinOpt.orElse(null),
                newTwin
        );
        log.debug("Twin updated for device {}. (Event created but not published)", request.deviceId());
    }

    public Optional<DigitalTwin> getTwin(UUID deviceId) {
        return registry.get(deviceId);
    }

    public List<DigitalTwin> getAllTwins() {
        return registry.listAll();
    }
}
