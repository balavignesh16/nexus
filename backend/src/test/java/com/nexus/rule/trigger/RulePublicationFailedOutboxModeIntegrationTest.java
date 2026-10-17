package com.nexus.rule.trigger;

import com.nexus.action.application.CommandDispatchCoordinator;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.action.outbox.BrokerCommandPublisher;
import com.nexus.action.outbox.CommandAckTimeoutScanner;
import com.nexus.action.outbox.CommandOutboxDispatcher;
import com.nexus.action.outbox.FakeBroker;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * End-to-end rule facts of the outbox lifecycle with a SUBSCRIBED rule (ledger P4-042, P4-056): the COMMAND_PUBLICATION_FAILED fact is
 * recorded exactly once, at the terminal outcome and never per attempt, and a reaction lost to a crash is recovered idempotently; the
 * persisted ACK timeout worker and the rule ACK-timeout scanner each own their own fact and never duplicate one another.
 */
@TestPropertySource(properties = {"nexus.command.outbox.enabled=true", "nexus.command.outbox.dispatcher-enabled=true"})
@Import(RulePublicationFailedOutboxModeIntegrationTest.Scripted.class)
class RulePublicationFailedOutboxModeIntegrationTest extends TypedTriggerSupport {

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        FakeBroker fakeBroker() { return new FakeBroker(); }
    }

    @Autowired CommandDispatchCoordinator dispatch;
    @Autowired CommandOutboxDispatcher outboxDispatcher;
    @Autowired CommandAckTimeoutScanner persistedTimeouts;
    @Autowired RuleAckTimeoutScanner ruleTimeouts;
    @Autowired FakeBroker fake;
    @SpyBean RuleSourceFacts sourceFacts;

    private UUID subscribed(UUID device, TriggerType type) {
        return typedRule(device, type + " rule", 1, List.of(new TriggerSubscription(type, Map.of())), List.of(), List.of(alert()));
    }

    private ActionExecutionRequest manualCommand(UUID device) {
        return new ActionExecutionRequest(UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
    }

    private String outboxState(UUID command) {
        return sql.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, command);
    }

    private static void await(BooleanSupplier c, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private int publicationFailedFacts(UUID command) {
        return count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='COMMAND_PUBLICATION_FAILED' AND source_key=?", command.toString());
    }

    @Test
    @Timeout(120)
    void theSubscribedPublicationFailedFactIsRecordedExactlyOnceAtTheTerminalOutcomeNotPerAttempt() throws Exception {
        fake.reset();
        fake.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_PUBLICATION_FAILED);
        var request = manualCommand(device);
        reset(sourceFacts);
        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        await(() -> "DEAD_LETTER".equals(outboxState(request.requestId())), "retry exhaustion", 60);
        await(() -> publicationFailedFacts(request.requestId()) == 1, "the terminal rule fact", 30);
        await(() -> matches(rule) == 1, "the routed rule match", 30);

        assertEquals(3, fake.calls.size(), "three not-sent attempts");
        assertEquals(1, publicationFailedFacts(request.requestId()), "ONE fact for the command, not one per attempt");
        assertEquals(1, matches(rule), "and the subscribed rule matched once");
        verify(sourceFacts, times(1)).publicationFailed(eq(request.requestId()), eq(device), any(), any());
        assertEquals("RETRY_EXHAUSTED", sql.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, request.requestId()));
    }

    @Test
    @Timeout(120)
    void aReactionLostToACrashIsRecoveredIdempotentlyAndTheSubscribedRuleStillMatchesExactlyOnce() throws Exception {
        fake.reset();
        fake.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_PUBLICATION_FAILED);
        var request = manualCommand(device);
        reset(sourceFacts);
        // the post-commit reaction "crashes": the rule fact is never recorded by the normal path
        doThrow(new IllegalStateException("process died before the rule fact")).doCallRealMethod().when(sourceFacts).publicationFailed(any(), any(), any(), any());

        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        await(() -> "DEAD_LETTER".equals(outboxState(request.requestId())), "retry exhaustion", 60);
        await(() -> {
            try { verify(sourceFacts, times(1)).publicationFailed(any(), any(), any(), any()); return true; } catch (AssertionError e) { return false; }
        }, "the (failing) first reaction", 30);
        assertEquals(0, publicationFailedFacts(request.requestId()), "the fact was lost");
        assertEquals(0, matches(rule));

        await(() -> Boolean.TRUE.equals(sql.queryForObject("SELECT terminal_at < now() - INTERVAL '5 seconds' FROM command_outbox WHERE command_id = ?", Boolean.class, request.requestId())),
                "the outcome to settle past the five second guard (database clock)", 40);
        doCallRealMethod().when(sourceFacts).publicationFailed(any(), any(), any(), any());
        assertTrue(outboxDispatcher.recoverReactions() >= 1, "the recovery scan finds the lost signal");
        assertEquals(1, publicationFailedFacts(request.requestId()), "recovered exactly once");
        assertEquals(1, matches(rule), "and the subscribed rule matches exactly once");
        int again = outboxDispatcher.recoverReactions();
        assertEquals(1, publicationFailedFacts(request.requestId()), "a second recovery pass is idempotent: still one fact");
        assertEquals(1, matches(rule));
        assertTrue(again >= 0);
    }

    @Test
    @Timeout(120)
    void thePersistedTimeoutWorkerAndTheRuleTimeoutScannerEachOwnTheirOwnFactAndNeverDuplicateOneAnother() throws Exception {
        fake.reset();                                                              // every publication is confirmed
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_ACK_TIMED_OUT);
        var request = manualCommand(device);
        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        await(() -> "PUBLISHED".equals(outboxState(request.requestId())), "publication", 30);
        sql.update("UPDATE action_history SET ack_deadline_at = clock_timestamp() - INTERVAL '2 seconds' WHERE request_id = ?", request.requestId());
        now = Instant.now().plusSeconds(10);                                       // the rule scanner judges by the (mocked) application clock

        reset(sourceFacts);
        assertEquals(1, persistedTimeouts.scan(100));
        org.mockito.Mockito.verifyNoInteractions(sourceFacts);                      // the persisted worker emits NO rule fact: that belongs to the rule scanner
        assertEquals(0, facts("COMMAND_ACK_TIMED_OUT", device));
        assertEquals(1, ruleTimeouts.scan(100));
        assertEquals(0, ruleTimeouts.scan(100), "the rule scanner observes it once");
        assertEquals(0, persistedTimeouts.scan(100), "and the persisted worker records it once");

        assertEquals(1, facts("COMMAND_ACK_TIMED_OUT", device), "exactly one rule fact");
        assertEquals(1, matches(rule));
        assertEquals(1, count("SELECT count(*) FROM command_state_history WHERE command_id=? AND fact_type='ACK_TIMED_OUT'", request.requestId()), "exactly one persisted timeout fact");
        assertEquals(1, fake.calls.size(), "neither worker resent the command");
    }
}
