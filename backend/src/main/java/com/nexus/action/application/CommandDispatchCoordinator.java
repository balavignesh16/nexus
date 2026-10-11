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

    public Outcome dispatch(ActionExecutionRequest request, ActionContext context,String correlationId) {
        Instant requestedAt = Instant.now();
        CommandExecutor.PreparedCommand prepared;
        try {
            prepared = acceptance.accept(request, context, requestedAt,correlationId);
        } catch (CommandExecutor.CommandRejectedException e) {
            return new Outcome(new ActionExecutionResult(request.requestId(), request,
                    ExecutionStatus.FAILURE, Instant.now(), e.getMessage()), false);
        }
        ActionExecutionResult result = executor.publishPrepared(request, prepared);
        // This conditional UPDATE changes only publish fields. An ACK can arrive first
        // without being erased, and a superseded desired row is never touched here.
        Instant publishedAt = result.status() == ExecutionStatus.SUCCESS ? clock.instant() : null;
        Instant recordedAt = publishedAt == null ? result.timestamp() : publishedAt;
        actions.updatePublishResultIfAccepted(request.requestId(), result.status(), recordedAt,
                publishedAt == null ? null : publishedAt.plus(ackTimeout), result.message());
        if (result.status() == ExecutionStatus.FAILURE && verification != null)
            verification.commandFailed(prepared.deviceId(),request.requestId(),"MQTT_PUBLICATION_FAILED");
        return new Outcome(result, true);
    }
}
