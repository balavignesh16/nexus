package com.nexus.device.domain.exception;

import java.util.UUID;

/**
 * A device cannot be deleted because deleting it would destroy command delivery state (Phase 4, contract R30).
 * Carries a stable machine code; the HTTP layer maps it to 409 with that code.
 */
public class DeviceDeletionBlockedException extends RuntimeException {

    /** At least one QUEUED, IN_FLIGHT or RETRY_WAIT command delivery obligation exists. */
    public static final String UNRESOLVED_COMMANDS = "DEVICE_HAS_UNRESOLVED_COMMANDS";
    /** Only resolved delivery records remain; retention has not purged them yet (fail closed). */
    public static final String DELIVERY_EVIDENCE = "DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE";

    private final String code;
    private final UUID deviceId;

    public DeviceDeletionBlockedException(String code, UUID deviceId) {
        super(messageFor(code, deviceId));
        this.code = code;
        this.deviceId = deviceId;
    }

    private static String messageFor(String code, UUID deviceId) {
        if (UNRESOLVED_COMMANDS.equals(code))
            return "Cannot delete Device " + deviceId + " because it has command delivery obligations that are not resolved. "
                    + "Wait for them to be published, expire or be dead-lettered.";
        return "Cannot delete Device " + deviceId + " because it still has command delivery records that retention has not purged.";
    }

    public String getCode() { return code; }
    public UUID getDeviceId() { return deviceId; }
}
