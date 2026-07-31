package com.nexus.action.application;

import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ActionPlanner {

    private static final Logger log = LoggerFactory.getLogger(ActionPlanner.class);

    private final ActionExecutionEngine executionEngine;

    public ActionPlanner(ActionExecutionEngine executionEngine) {
        this.executionEngine = executionEngine;
    }

    @EventListener
    public void onRuleMatched(RuleMatchedEvent event) {
        if (event.result() == null || event.result().rule() == null || event.result().rule().actions() == null) {
            return;
        }

        ActionContext context = new ActionContext(
                event.result().event(),
                event.result().rule()
        );

        List<ActionExecutionRequest> requests = new ArrayList<>();
        
        for (RuleAction action : event.result().rule().actions()) {
            try {
                // Convert string actionType to enum safely
                ActionType type = ActionType.valueOf(action.actionType().toUpperCase().replace("-", "_"));
                
                ActionExecutionRequest request = new ActionExecutionRequest(
                        UUID.randomUUID(),
                        event.matchId(),
                        type,
                        action.parameters()
                );
                requests.add(request);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown ActionType requested: {}. It will be ignored.", action.actionType());
            }
        }

        if (!requests.isEmpty()) {
            executionEngine.process(requests, context, event.correlationId());
        }
    }
}
