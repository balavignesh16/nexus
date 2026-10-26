package com.nexus.action.outbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flow-control limits (contract R18) are validated at startup: no unbounded, negative, zero or contradictory value is accepted. */
class CommandFlowSettingsTest {

    @Test
    void theShippedDefaultsAreTheContractDefaults() {
        CommandFlowSettings s = new CommandFlowSettings(500, 10, 20);
        assertEquals(500, s.globalQueueCap());
        assertEquals(10, s.deviceQueueCap());
        assertEquals(20, s.publishRatePerSecond());
        assertTrue(s.problems().isEmpty());
    }

    @Test
    void everyBoundaryIsAcceptedAndOneBeyondItIsRefused() {
        assertDoesNotThrow(() -> new CommandFlowSettings(1, 1, 1));
        assertDoesNotThrow(() -> new CommandFlowSettings(CommandFlowSettings.MAX_GLOBAL_QUEUE_CAP, CommandFlowSettings.MAX_DEVICE_QUEUE_CAP, CommandFlowSettings.MAX_PUBLISH_RATE));
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(0, 1, 1), "global cap 0");
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(CommandFlowSettings.MAX_GLOBAL_QUEUE_CAP + 1, 1, 1), "global cap above the ceiling");
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(500, 0, 20), "device cap 0");
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(2_000_000, CommandFlowSettings.MAX_DEVICE_QUEUE_CAP + 1, 20), "device cap above the ceiling");
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(500, 10, 0), "rate 0 would block every publication forever");
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(500, 10, CommandFlowSettings.MAX_PUBLISH_RATE + 1), "rate above the ceiling");
    }

    @Test
    void negativeAndOverflowProneValuesAreRefused() {
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(-1, 10, 20));
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(500, -10, 20));
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(500, 10, -20));
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(Integer.MAX_VALUE, 10, 20));
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(500, 10, Integer.MAX_VALUE));
        assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void aDeviceCapAboveTheGlobalCapIsAContradictionAndEqualIsAllowed() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(5, 6, 20));
        assertTrue(e.getMessage().contains("device-queue-cap must not exceed global-queue-cap"), e.getMessage());
        assertDoesNotThrow(() -> new CommandFlowSettings(5, 5, 20));
    }

    @Test
    void allProblemsAreReportedTogether() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CommandFlowSettings(0, 0, 0));
        assertTrue(e.getMessage().startsWith("Invalid nexus.command.flow configuration:"), e.getMessage());
        assertTrue(e.getMessage().contains("global-queue-cap") && e.getMessage().contains("device-queue-cap") && e.getMessage().contains("publish-rate-per-second"), e.getMessage());
    }
}
