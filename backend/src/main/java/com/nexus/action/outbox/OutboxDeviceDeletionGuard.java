package com.nexus.action.outbox;

import com.nexus.device.application.DeviceDeletionGuard;
import com.nexus.device.domain.exception.DeviceDeletionBlockedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Refuses device deletion while the device has command delivery obligations or delivery records (contract R30).
 * Independent of the outbox feature flag: rows may exist whatever the flag says. The V38 database trigger and the
 * ON DELETE RESTRICT key enforce the same rule for every other deletion path.
 */
@Component
public class OutboxDeviceDeletionGuard implements DeviceDeletionGuard {

    private final CommandOutboxRepository outbox;

    public OutboxDeviceDeletionGuard(CommandOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional(readOnly = true)
    public void assertDeletable(UUID deviceId) {
        if (outbox.existsByDeviceIdAndStateIn(deviceId, EnumSet.of(OutboxState.QUEUED, OutboxState.IN_FLIGHT, OutboxState.RETRY_WAIT)))
            throw new DeviceDeletionBlockedException(DeviceDeletionBlockedException.UNRESOLVED_COMMANDS, deviceId);
        if (outbox.existsByDeviceId(deviceId))
            throw new DeviceDeletionBlockedException(DeviceDeletionBlockedException.DELIVERY_EVIDENCE, deviceId);
    }
}
