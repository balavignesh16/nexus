package com.nexus.rule.application;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole Phase 3 {@link RuleCommandAuthorizationIntegrationTest} suite (43 tests: every authorization gate, version, pause,
 * epoch and ABA race, unit-safety and real-MQTT scenario) with the command outbox ENABLED. Six of its tests encode the legacy
 * synchronous mechanics; they are dual-mode in the base class: with the flag ON they assert the new lifecycle (ACCEPTED first,
 * delivered by the dispatcher, direct publication never used) instead of the legacy call. Nothing is skipped or weakened.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RuleCommandAuthorizationOutboxModeIntegrationTest extends RuleCommandAuthorizationIntegrationTest {
}
