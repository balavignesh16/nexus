package com.nexus.action.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Bounded admission of new delivery obligations (contract R18, ledger P4-087 to P4-091). Called by acceptance (T1, the REQUIRES_NEW
 * acceptance transaction) after authorisation and BEFORE the first durable write.
 *
 * <p>Capacity is the number of unresolved obligations: QUEUED, IN_FLIGHT and RETRY_WAIT. A slot is released the moment the obligation
 * becomes PUBLISHED or DEAD_LETTER. A retry is the same obligation, so it never takes a second slot.
 *
 * <p><b>Why the counts are race-safe.</b> The per-device count is taken under the device row lock that acceptance already holds, so two
 * acceptances for one device are serialised. The global count spans devices, which the device lock cannot serialise, so a transaction-scoped
 * advisory lock (key space 4601, object 1) is taken first: acceptances are admitted one at a time, each sees every earlier acceptance's
 * committed obligation, and the lock is held until this transaction commits or rolls back. Lock order: device row, then this lock, then the
 * outbox insert and the history lock (always last). Nothing else ever takes this lock (the dispatcher uses a different one), and no
 * transaction that holds the history lock or an outbox row lock ever waits for it, so it cannot join a cycle.
 */
@Component
public class CommandAdmission {
    static final int LOCK_CLASS = 4601;
    static final int ADMISSION_LOCK = 1;
    /** The states that occupy capacity; the literal list matches the partial index idx_command_outbox_device_unresolved (V37). */
    static final String UNRESOLVED = "state IN ('QUEUED', 'RETRY_WAIT', 'IN_FLIGHT')";

    private final JdbcTemplate jdbc;
    private final CommandFlowSettings settings;
    private final MeterRegistry meters;

    @Autowired
    public CommandAdmission(JdbcTemplate jdbc, CommandFlowSettings settings, ObjectProvider<MeterRegistry> meters) {
        this(jdbc, settings, meters.getIfAvailable());
    }

    CommandAdmission(JdbcTemplate jdbc, CommandFlowSettings settings, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.meters = meters;
    }

    /**
     * Admits one more obligation for {@code deviceId} or throws {@link CommandQueueFullException}. Must run inside the acceptance
     * transaction with the device row lock held (MANDATORY: it refuses to run without a transaction).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admit(UUID deviceId, boolean ruleOrigin) {
        int deviceUnresolved = jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE device_id = ? AND " + UNRESOLVED, Integer.class, deviceId);
        if (deviceUnresolved >= settings.deviceQueueCap()) refuse(CommandQueueFullException.Scope.DEVICE, settings.deviceQueueCap(), deviceId, ruleOrigin);
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> { }, LOCK_CLASS, ADMISSION_LOCK);
        int globalUnresolved = jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE " + UNRESOLVED, Integer.class);
        if (globalUnresolved >= settings.globalQueueCap()) refuse(CommandQueueFullException.Scope.GLOBAL, settings.globalQueueCap(), deviceId, ruleOrigin);
    }

    private void refuse(CommandQueueFullException.Scope scope, int limit, UUID deviceId, boolean ruleOrigin) {
        if (meters != null) meters.counter("nexus.command.flow.rejected", "scope", scope.name(), "origin", ruleOrigin ? "RULE" : "MANUAL").increment();
        throw new CommandQueueFullException(scope, limit, deviceId);
    }

    /** Current number of unresolved obligations (a plain read, for metrics and tests). */
    public int unresolvedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE " + UNRESOLVED, Integer.class);
    }
}
