package com.nexus.device.application;

import java.util.UUID;

/**
 * Port through which other modules can veto the deletion of a device because deleting it would destroy state that must
 * outlive it (Phase 4: command delivery obligations). Implemented outside the device package so the device module does not
 * depend on the action module. The database enforces the same rule independently; this port only gives a typed,
 * well-before-the-database rejection.
 */
public interface DeviceDeletionGuard {
    /** @throws com.nexus.device.domain.exception.DeviceDeletionBlockedException when the device must not be deleted */
    void assertDeletable(UUID deviceId);
}
