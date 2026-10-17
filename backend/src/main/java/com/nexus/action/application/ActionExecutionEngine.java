package com.nexus.action.application;

import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutedEvent;
import com.nexus.action.domain.ActionExecutionFailedEvent;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.action.domain.ActionType;
import org.springframework.beans.factory.annotation.Autowired;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ActionExecutionEngine {
    private com.nexus.rule.application.RuleNonCommandExecutionService nonCommand;
    @Autowired
    void setNonCommand(com.nexus.rule.application.RuleNonCommandExecutionService service){this.nonCommand=service;}

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionEngine.class);

    private final ActionExecutorRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final RealtimePublisher realtimePublisher;
    private final PipelineService pipelineService;
    private final CommandDispatchCoordinator commandDispatch;
    private com.nexus.rule.plan.RuleCandidateOutcomes outcomes;
    @Autowired(required = false)
    void setOutcomes(com.nexus.rule.plan.RuleCandidateOutcomes outcomes) { this.outcomes = outcomes; }

    public ActionExecutionEngine(ActionExecutorRegistry registry, ApplicationEventPublisher eventPublisher,
                                 RealtimePublisher realtimePublisher, PipelineService pipelineService) {
        this(registry, eventPublisher, realtimePublisher, pipelineService, null);
    }

    @Autowired
    public ActionExecutionEngine(ActionExecutorRegistry registry, ApplicationEventPublisher eventPublisher,
                                 RealtimePublisher realtimePublisher, PipelineService pipelineService,
                                 CommandDispatchCoordinator commandDispatch) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.realtimePublisher = realtimePublisher;
        this.pipelineService = pipelineService;
        this.commandDispatch = commandDispatch;
    }

    public void process(List<ActionExecutionRequest> requests, ActionContext context, String correlationId) {
        for (ActionExecutionRequest request : requests) {
            long stageStart = System.currentTimeMillis();
            boolean durablyAcceptedCommand = false;
            ActionExecutionResult result;
            try {
            if (request.actionType() == ActionType.SEND_COMMAND && commandDispatch != null) {
                CommandDispatchCoordinator.Outcome outcome = commandDispatch.dispatch(request, context,correlationId);
                result = outcome.result();
                durablyAcceptedCommand = outcome.accepted();
            } else {
                result = executeSingleRequest(request, context);
            }
            } catch(com.nexus.rule.application.RuleExecutionSuppressedException suppression) {
                log.warn("Rule action suppressed before acceptance: {} {}",request.requestId(),suppression.getMessage());
                if (outcomes != null) outcomes.denied(request.requestId(), suppression.getMessage());   // evidence on the persisted candidate
                continue; // Not MQTT FAILURE, and no ActionHistory acceptance/result is fabricated.
            }

            pipelineService.recordTrace(
                    PipelineTrace.of(correlationId, deviceIdFromRequest(request), PipelineStage.ACTION, stageStart,
                            result.status() == ExecutionStatus.ACCEPTED_FOR_DISPATCH ? "ACCEPTED"
                                    : result.status() != ExecutionStatus.FAILURE ? "SUCCESS" : "FAILURE", result.status().name(), null));

            // Persist action history (synchronous listener) before broadcasting,
            // so the realtime message is only sent once the result is durable.
            if (!durablyAcceptedCommand) {
                if (result.status() == ExecutionStatus.FAILURE) {
                    eventPublisher.publishEvent(new ActionExecutionFailedEvent(result, correlationId));
                } else {
                    eventPublisher.publishEvent(new ActionExecutedEvent(result, correlationId));
                }
            }

            // Broadcast to WebSocket clients
            realtimePublisher.publishActionExecuted(result, correlationId);
        }
    }

    /**
     * Tier 5D: mirrors the same defensive extraction ActionHistoryRecord.fromResult()
     * already uses - deliberately reads request.parameters() rather than
     * ActionContext.triggeringEvent(), which may be null for a manually
     * triggered command (no rule match, no triggering event).
     */
    private String deviceIdFromRequest(ActionExecutionRequest request) {
        if (request.parameters() == null || !request.parameters().containsKey("deviceId")) {
            return null;
        }
        try {
            return java.util.UUID.fromString(request.parameters().get("deviceId").toString()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private ActionExecutionResult executeSingleRequest(ActionExecutionRequest request, ActionContext context) {
        Optional<ActionExecutor> executorOpt = registry.resolve(request.actionType());
        
        if (executorOpt.isEmpty()) {
            log.warn("No executor found for ActionType: {}", request.actionType());
            return new ActionExecutionResult(
                    request.requestId(),
                    request,
                    ExecutionStatus.UNSUPPORTED,
                    Instant.now(),
                    "No executor found for action type: " + request.actionType()
            );
        }

        try {
            if(nonCommand!=null && context!=null && context.matchedRule()!=null)
                return nonCommand.execute(request,context,executorOpt.get());
            return executorOpt.get().execute(request, context);
        } catch(com.nexus.rule.application.RuleExecutionSuppressedException suppression) {
            throw suppression;
        } catch (Exception e) {
            log.error("Execution failed for request {}", request.requestId(), e);
            return new ActionExecutionResult(
                    request.requestId(),
                    request,
                    ExecutionStatus.FAILURE,
                    Instant.now(),
                    e.getMessage()
            );
        }
    }
}
