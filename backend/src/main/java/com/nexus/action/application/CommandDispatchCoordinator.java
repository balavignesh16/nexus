package com.nexus.action.application;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.nexus.twin.application.VerificationService;

/** Coordinates one accepted command without publishing inside the acceptance transaction. */
@Component
public class CommandDispatchCoordinator {
    public record Outcome(ActionExecutionResult result, boolean accepted) {}
    private final CommandAcceptanceService acceptance;
    private final CommandExecutor executor;
    private final ActionHistoryRepository actions;
    private final Clock clock;
    private final Duration ackTimeout;
    private VerificationService verification;

    @Autowired
    void setVerification(VerificationService verification){this.verification=verification;}
    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired
    void setRuleFacts(com.nexus.rule.trigger.RuleSourceFacts ruleFacts,org.springframework.transaction.PlatformTransactionManager transactions){this.ruleFacts=ruleFacts;this.transactions=transactions;}

    public CommandDispatchCoordinator(CommandAcceptanceService acceptance, CommandExecutor executor,
            ActionHistoryRepository actions) {
        this(acceptance,executor,actions,Clock.systemUTC(),Duration.ofSeconds(30));
    }

    @Autowired
    public CommandDispatchCoordinator(CommandAcceptanceService acceptance, CommandExecutor executor,
            ActionHistoryRepository actions, Clock clock,
            @Value("${nexus.command.ack-timeout:PT30S}") Duration ackTimeout) {
        if (ackTimeout.isZero() || ackTimeout.isNegative()) throw new IllegalArgumentException("ACK timeout must be positive");
        this.acceptance=acceptance; this.executor=executor; this.actions=actions;
        this.clock=clock; this.ackTimeout=ackTimeout;
    }

    // Phase 4 (P4.2): with the outbox flag ON, acceptance also writes the delivery obligation and the dispatcher is the only
    // publisher. The coordinator then reports ACCEPTED_FOR_DISPATCH, never SUCCESS: nothing has been published yet.
    private com.nexus.action.outbox.CommandOutboxSettings outboxSettings;
    private org.springframework.beans.factory.ObjectProvider<com.nexus.action.outbox.CommandOutboxDispatcher> outboxDispatcher;
    @Autowired(required = false)
    void setOutbox(com.nexus.action.outbox.CommandOutboxSettings settings,
            org.springframework.beans.factory.ObjectProvider<com.nexus.action.outbox.CommandOutboxDispatcher> dispatcher) {
        this.outboxSettings=settings; this.outboxDispatcher=dispatcher;
    }

    /**
     * Enabling the outbox must never create obligations that nothing services: startup fails if the outbox is enabled but no
     * dispatcher bean exists. (The dispatcher is unconditional, so this is a guard against a future wiring change.)
     */
    @jakarta.annotation.PostConstruct
    void verifyOutboxWiring() {
        if (outboxSettings != null && outboxSettings.enabled() && (outboxDispatcher == null || outboxDispatcher.getIfAvailable() == null))
            throw new IllegalStateException("nexus.command.outbox.enabled is true but no CommandOutboxDispatcher bean exists to service the obligations");
    }

    public Outcome dispatch(ActionExecutionRequest request, ActionContext context,String correlationId) {
        Instant requestedAt = Instant.now();
        CommandExecutor.PreparedCommand prepared;
        try {
            prepared = acceptance.accept(request, context, requestedAt,correlationId);
        } catch (CommandExecutor.CommandRejectedException e) {
            return new Outcome(new ActionExecutionResult(request.requestId(), request,
                    ExecutionStatus.FAILURE, Instant.now(), e.getMessage()), false);
        }
        if (outboxSettings != null && outboxSettings.enabled()) {
            // acceptance has COMMITTED (it is REQUIRES_NEW); the nudge only triggers the normal claim pass
            var dispatcher = outboxDispatcher == null ? null : outboxDispatcher.getIfAvailable();
            if (dispatcher != null) dispatcher.nudge();
            return new Outcome(new ActionExecutionResult(request.requestId(), request, ExecutionStatus.ACCEPTED_FOR_DISPATCH,
                    clock.instant(), "Command accepted and queued for delivery; not yet published"), true);
        }
        ActionExecutionResult result = executor.publishPrepared(request, prepared);
        // This conditional UPDATE changes only publish fields. An ACK can arrive first
        // without being erased, and a superseded desired row is never touched here.
        Instant publishedAt = result.status() == ExecutionStatus.SUCCESS ? clock.instant() : null;
        Instant recordedAt = publishedAt == null ? result.timestamp() : publishedAt;
        Instant deadline = publishedAt == null ? null : publishedAt.plus(ackTimeout);
        int recorded = actions.updatePublishResultIfAccepted(request.requestId(), result.status(), recordedAt, deadline, result.message());
        if (recorded == 1 && result.status() == ExecutionStatus.FAILURE && ruleFacts != null && transactions != null) {
            // The P2.4 publication-failure row above is the authoritative lifecycle fact and is never coupled to Rule storage.
            // The derived source fact is committed on its own; if that fails no Rule can fire (fail closed), nothing is retried or resent.
            try {
                new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx ->
                    ruleFacts.publicationFailed(request.requestId(), prepared.deviceId(), recordedAt, result.message()));
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(CommandDispatchCoordinator.class).error("RULE_SOURCE_FACT_FAILED publication-failure command={}", request.requestId(), failure);
            }
        }
        if (result.status() == ExecutionStatus.FAILURE && verification != null)
            verification.commandFailed(prepared.deviceId(),request.requestId(),"MQTT_PUBLICATION_FAILED");
        return new Outcome(result, true);
    }
}
