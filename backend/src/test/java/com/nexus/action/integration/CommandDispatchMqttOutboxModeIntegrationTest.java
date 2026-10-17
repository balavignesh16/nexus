package com.nexus.action.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole {@link CommandDispatchMqttIntegrationTest} suite with the command outbox ENABLED against a real Mosquitto broker and
 * PostgreSQL: the same acceptance, desired-state, ACK and trace scenarios, with per-test isolated devices (so the V38 deletion
 * guard is respected) and the queued-to-published lifecycle (ACCEPTED_FOR_DISPATCH first, SUCCESS only after the dispatcher
 * published). The flag-OFF suite is unchanged and keeps proving the legacy synchronous behaviour.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@Testcontainers
class CommandDispatchMqttOutboxModeIntegrationTest extends CommandDispatchMqttIntegrationTest {
}
