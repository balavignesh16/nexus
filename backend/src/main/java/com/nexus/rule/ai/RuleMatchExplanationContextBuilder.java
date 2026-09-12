package com.nexus.rule.ai;

import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Deterministic evidence retrieval only (Tier 4 Phase 1) - no reasoning, no
 * text generation, no AI. Exactly one call to each of RuleMatchRepository,
 * DomainEventRepository and ActionHistoryRepository per build(). The
 * historical ruleSnapshot/evaluationEvidence come directly from the
 * persisted RuleMatchEntity, never from RuleRegistry and never
 * re-evaluated.
 */
@Component
public class RuleMatchExplanationContextBuilder {

    private final RuleMatchRepository ruleMatchRepository;
    private final DomainEventRepository domainEventRepository;
    private final ActionHistoryRepository actionHistoryRepository;

    public RuleMatchExplanationContextBuilder(RuleMatchRepository ruleMatchRepository,
                                               DomainEventRepository domainEventRepository,
                                               ActionHistoryRepository actionHistoryRepository) {
        this.ruleMatchRepository = ruleMatchRepository;
        this.domainEventRepository = domainEventRepository;
        this.actionHistoryRepository = actionHistoryRepository;
    }

    public RuleMatchExplanationContext build(UUID matchId) {
        RuleMatchEntity match = ruleMatchRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("RuleMatch", matchId.toString()));

        DomainEvent event = domainEventRepository.findById(match.getEventId()).orElse(null);

        UUID deviceId = event != null ? event.deviceId() : null;
        DomainEventSummary domainEventSummary = event != null
                ? new DomainEventSummary(
                        event.eventId(),
                        event.deviceId(),
                        event.eventType() != null ? event.eventType().name() : null,
                        event.timestamp(),
                        event.source(),
                        event.severity())
                : null;

        List<ActionSummary> actions = actionHistoryRepository.findByMatchId(matchId).stream()
                .map(this::toActionSummary)
                .toList();

        return new RuleMatchExplanationContext(
                match.getMatchId(),
                match.getRuleId(),
                match.getRuleSnapshot(),
                match.getEventId(),
                match.getCorrelationId(),
                match.getEvaluationEvidence(),
                match.getMatchedAt(),
                match.getMessage(),
                deviceId,
                domainEventSummary,
                actions
        );
    }

    private ActionSummary toActionSummary(ActionHistoryRecord record) {
        return new ActionSummary(
                record.getRequestId(),
                record.getActionType() != null ? record.getActionType().name() : null,
                record.getStatus() != null ? record.getStatus().name() : null,
                record.getTimestamp(),
                record.getMessage()
        );
    }
}
