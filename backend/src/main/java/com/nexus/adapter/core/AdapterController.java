package com.nexus.adapter.core;

import com.nexus.adapter.spi.TransportKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only adapter diagnostics (adapter-contract.md section 17) and the guarded device transport assignment (decision D3). The
 * assignment is the only thing that selects a device's adapter. Nothing here creates, rotates, revokes or lists credentials (D7).
 */
@RestController
@RequestMapping("/api/v1")
public class AdapterController {
    public record TransportRequest(@NotNull(message = "transport is required") String transport) {}

    public record TransportResponse(UUID deviceId, String transport) {}

    private final AdapterRegistry registry;
    private final TransportAssignments assignments;

    public AdapterController(AdapterRegistry registry, TransportAssignments assignments) {
        this.registry = registry;
        this.assignments = assignments;
    }

    @GetMapping("/adapters")
    public List<AdapterRegistry.Report> adapters() {
        return registry.reports();
    }

    @GetMapping("/devices/{id}/transport")
    public ResponseEntity<?> transport(@PathVariable UUID id) {
        Optional<TransportKind> assigned = assignments.assignedTo(id);
        return assigned.<ResponseEntity<?>>map(kind -> ResponseEntity.ok(new TransportResponse(id, kind.name())))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "Device not found"));
    }

    @PutMapping("/devices/{id}/transport")
    public ResponseEntity<?> assign(@PathVariable UUID id, @Valid @RequestBody TransportRequest request) {
        Optional<TransportKind> kind = TransportKind.parse(request.transport());
        if (kind.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Unknown transport; allowed: MQTT_JSON, HTTP_WEBHOOK, SIMULATOR");
        }
        // MQTT_JSON is the legacy default and is served by the existing listener until the MQTT adapter is registered (P5.3), so returning a device
        // to it is always allowed; any other transport needs a registered adapter, so a device is never assigned to a transport nothing serves.
        if (kind.get() != TransportKind.MQTT_JSON && registry.forTransport(kind.get()).isEmpty()) {
            return error(HttpStatus.CONFLICT, "No adapter is registered for transport " + kind.get());
        }
        return switch (assignments.assign(id, kind.get())) {
            case DEVICE_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "Device not found");
            case COMMANDS_IN_PROGRESS -> error(HttpStatus.CONFLICT, "The device has commands that are still being delivered; the transport cannot change now");
            case UPDATED, UNCHANGED -> ResponseEntity.ok(new TransportResponse(id, kind.get().name()));
        };
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "error", status.getReasonPhrase(), "message", message));
    }
}
