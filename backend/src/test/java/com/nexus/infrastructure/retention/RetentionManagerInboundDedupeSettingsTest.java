package com.nexus.infrastructure.retention;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** The inbox window cannot be configured below the documented floor (R19), and the default is the approved 168 hours. */
class RetentionManagerInboundDedupeSettingsTest {
    private static RetentionManager manager(int hours, int batchSize, int maxBatches) {
        RetentionManager manager = new RetentionManager(mock(EntityManager.class), mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(manager, "inboundDedupeHours", hours);
        ReflectionTestUtils.setField(manager, "inboundDedupeBatchSize", batchSize);
        ReflectionTestUtils.setField(manager, "inboundDedupeMaxBatches", maxBatches);
        return manager;
    }

    @Test
    void aWindowShorterThanTheFloorIsRefusedAtStartup() {
        assertEquals(24, RetentionManager.MIN_INBOUND_DEDUPE_HOURS);
        assertThrows(IllegalStateException.class, () -> manager(23, 1000, 100).validateInboundDedupeSettings());
        assertThrows(IllegalStateException.class, () -> manager(0, 1000, 100).validateInboundDedupeSettings());
        assertThrows(IllegalStateException.class, () -> manager(168, 0, 100).validateInboundDedupeSettings());
        assertThrows(IllegalStateException.class, () -> manager(168, 1000, 0).validateInboundDedupeSettings());
        assertDoesNotThrow(() -> manager(24, 1000, 100).validateInboundDedupeSettings());
        assertDoesNotThrow(() -> manager(168, 1000, 100).validateInboundDedupeSettings());
    }

    @Test
    void aManagerBuiltWithoutSpringStillDefaultsToTheSafeWindow() {
        RetentionManager manager = new RetentionManager(mock(EntityManager.class), mock(PlatformTransactionManager.class));
        assertEquals(168, ReflectionTestUtils.getField(manager, "inboundDedupeHours"));
        assertDoesNotThrow(manager::validateInboundDedupeSettings);
    }
}
