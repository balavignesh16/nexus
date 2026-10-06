package com.nexus.action.application;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Coordinates one accepted command without publishing inside the acceptance transaction. */
@Component
public class CommandDispatchCoordinator {
    public record Outcome(ActionExecutionResult result, boolean accepted) {}
    private final CommandAcceptanceService acceptance;
    private final CommandExecutor executor;
    private final ActionHistoryRepository actions;

    public CommandDispatchCoordinator(CommandAcceptanceService acceptance, CommandExecutor executor,
            ActionHistoryRepository actions) {
        this.acceptance=acceptance; this.executor=executor; this.actions=actions;
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
        actions.updatePublishResultIfAccepted(request.requestId(), result.status(), result.timestamp(), result.message());
        return new Outcome(result, true);
    }
}
