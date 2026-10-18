package com.nexus.adapter.core;

import com.nexus.adapter.spi.TransportKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-side transport assignment of a device (decision D3), read from and written to {@code devices.transport} (V41). Nothing a
 * message says about itself ever selects a transport; this table does. A device that is unknown has no assignment.
 */
@Component
public class TransportAssignments {
    public enum Result { UPDATED, UNCHANGED, DEVICE_NOT_FOUND, COMMANDS_IN_PROGRESS }

    private final JdbcTemplate jdbc;

    public TransportAssignments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TransportKind> assignedTo(UUID deviceId) {
        List<String> found = jdbc.queryForList("SELECT transport FROM devices WHERE id = ?", String.class, deviceId);
        return found.isEmpty() ? Optional.empty() : TransportKind.parse(found.get(0));
    }

    /**
     * Changes the assignment. A transport is never changed under a command that is still being delivered: while the device has a command
     * that is QUEUED, IN_FLIGHT or in RETRY_WAIT the change is refused, so a delivery cannot be split across two transports.
     */
    @Transactional
    public Result assign(UUID deviceId, TransportKind kind) {
        List<String> current = jdbc.queryForList("SELECT transport FROM devices WHERE id = ? FOR UPDATE", String.class, deviceId);
        if (current.isEmpty()) return Result.DEVICE_NOT_FOUND;
        if (kind.name().equals(current.get(0))) return Result.UNCHANGED;
        Integer unresolved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_outbox WHERE device_id = ? AND state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT')", Integer.class, deviceId);
        if (unresolved != null && unresolved > 0) return Result.COMMANDS_IN_PROGRESS;
        jdbc.update("UPDATE devices SET transport = ? WHERE id = ?", kind.name(), deviceId);
        return Result.UPDATED;
    }
}
