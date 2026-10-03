package com.nexus.twin.api;

import com.nexus.twin.api.dto.DigitalTwinResponse;
import com.nexus.twin.application.DigitalTwinService;
import com.nexus.twin.domain.DigitalTwin;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DigitalTwinControllerTest {

    private final DigitalTwinService service = mock(DigitalTwinService.class);
    private final DigitalTwinController controller = new DigitalTwinController(service);

    @Test
    void getTwin_aggregatesAllCapabilityStatesForTheDevice() {
        UUID deviceId = UUID.randomUUID();
        List<DigitalTwin> states = statesFor(deviceId);
        when(service.getTwinCapabilities(deviceId)).thenReturn(states);

        ResponseEntity<DigitalTwinResponse> response = controller.getTwin(deviceId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("HUMIDITY", "TEMPERATURE"),
                response.getBody().reportedState().keySet().stream().toList());
    }

    @Test
    void getAllTwins_keepsOneResponsePerDeviceAndIncludesEveryCapability() {
        UUID deviceId = UUID.randomUUID();
        List<DigitalTwin> states = statesFor(deviceId);
        when(service.getAllTwins()).thenReturn(List.of(states.get(1)));
        when(service.getTwinCapabilities(deviceId)).thenReturn(states);

        List<DigitalTwinResponse> body = controller.getAllTwins(100).getBody();

        assertEquals(1, body.size());
        assertEquals(2, body.getFirst().reportedState().size());
    }

    private static List<DigitalTwin> statesFor(UUID deviceId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return List.of(
                new DigitalTwin(deviceId, now, "TEMPERATURE", 22.5, "CELSIUS", now, Map.of()),
                new DigitalTwin(deviceId, now.plusSeconds(1), "HUMIDITY", 55.0, "PERCENT", now.plusSeconds(1), Map.of())
        );
    }
}
