package com.nexus.rule.plan;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import java.util.UUID;

/** One action of one persisted match, with the command request id that will be authorized for it. */
public record ActionCandidate(UUID candidateId,UUID matchId,Rule rule,int ordinal,RuleAction action,UUID requestId,
        String targetKey,String equivalenceKey,int priority,String correlationId) {
    public long revision(){return rule.executionIdentity().ruleRevision();}
    public UUID versionId(){return rule.executionIdentity().ruleVersionId();}
}
