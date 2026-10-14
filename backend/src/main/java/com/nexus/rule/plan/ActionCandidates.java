package com.nexus.rule.plan;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleMatchedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The single, side-effect-free construction of action candidates from a batch of matches. Production planning and the dry-run engine both
 * call it, so a simulated candidate has exactly the target, equivalence key, priority and ordinal a real one would.
 */
public final class ActionCandidates {
    private ActionCandidates() {}

    public static List<ActionCandidate> build(List<RuleMatchedEvent> matches) {
        var candidates = new ArrayList<ActionCandidate>();
        for (RuleMatchedEvent match : matches) {
            Rule rule = match.result().rule();
            if (rule.actions() == null || rule.executionIdentity() == null) continue;   // an unpinned rule can never be authorized
            for (int i = 0; i < rule.actions().size(); i++) {
                RuleAction action = rule.actions().get(i);
                boolean command = "SEND_COMMAND".equals(action.actionType());
                candidates.add(new ActionCandidate(UUID.randomUUID(), match.matchId(), rule, i, action, UUID.randomUUID(),
                    command ? ActionArbiter.targetKey(action) : null, ActionArbiter.equivalenceKey(action), rule.priority(), match.correlationId()));
            }
        }
        return candidates;
    }
}
