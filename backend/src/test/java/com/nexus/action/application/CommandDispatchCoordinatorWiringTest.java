package com.nexus.action.application;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.outbox.CommandOutboxDispatcher;
import com.nexus.action.outbox.CommandOutboxSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Enabling the outbox must never create obligations that nothing services. */
class CommandDispatchCoordinatorWiringTest {

    private static CommandOutboxSettings settings(boolean enabled) {
        return new CommandOutboxSettings(enabled, Duration.ofSeconds(15), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0,
                Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1));
    }

    private CommandDispatchCoordinator coordinator(CommandOutboxSettings settings, DefaultListableBeanFactory beans) {
        CommandDispatchCoordinator c = new CommandDispatchCoordinator(mock(CommandAcceptanceService.class), mock(CommandExecutor.class), mock(ActionHistoryRepository.class));
        c.setOutbox(settings, beans.getBeanProvider(CommandOutboxDispatcher.class));
        return c;
    }

    @Test
    void anEnabledOutboxWithoutADispatcherFailsStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> coordinator(settings(true), new DefaultListableBeanFactory()).verifyOutboxWiring());
        assertTrue(e.getMessage().contains("no CommandOutboxDispatcher bean"), e.getMessage());
    }

    @Test
    void anEnabledOutboxWithADispatcherAndADisabledOutboxWithoutOneAreBothFine() {
        DefaultListableBeanFactory with = new DefaultListableBeanFactory();
        with.registerSingleton("dispatcher", mock(CommandOutboxDispatcher.class));
        assertDoesNotThrow(() -> coordinator(settings(true), with).verifyOutboxWiring());
        assertDoesNotThrow(() -> coordinator(settings(false), new DefaultListableBeanFactory()).verifyOutboxWiring());
    }
}
