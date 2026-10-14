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
    private final PersistedArbitrationReader arbitration;

    @org.springframework.beans.factory.annotation.Autowired
    public RuleMatchExplanationContextBuilder(RuleMatchRepository ruleMatchRepository,
                                               DomainEventRepository domainEventRepository,
                                               ActionHistoryRepository actionHistoryRepository,
                                               PersistedArbitrationReader arbitration) {
        this.ruleMatchRepository = ruleMatchRepository;
        this.domainEventRepository = domainEventRepository;
        this.actionHistoryRepository = actionHistoryRepository;
        this.arbitration = arbitration;
    }

    /** Without arbitration retrieval: the context then carries no candidates (an explanation will say none are recorded). */
    public RuleMatchExplanationContextBuilder(RuleMatchRepository ruleMatchRepository, DomainEventRepository domainEventRepository, ActionHistoryRepository actionHistoryRepository) {
        this(ruleMatchRepository, domainEventRepository, actionHistoryRepository, null);
    }

    public RuleMatchExplanationContext build(UUID matchId) {
        RuleMatchEntity match = ruleMatchRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("RuleMatch", matchId.toString()));

        // Typed matches (manual, schedule, temporal, telemetry facts, chains) have no DomainEvent; never invent one.
        DomainEvent event = match.getEventId() == null ? null : domainEventRepository.findById(match.getEventId()).orElse(null);

        UUID deviceId = event != null ? event.deviceId() : triggerDevice(match.getEvaluationEvidence());
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
                actions,
                match.getRuleVersionId() == null ? null : new VersionAttribution(match.getRuleVersionId(), match.getRuleRevision(), match.getExecutionEpoch()),
                arbitration == null ? List.of() : arbitration.forMatch(matchId)
        );
    }

    /** The committed trigger device recorded in the frozen-context evidence, when the match has one. */
    @SuppressWarnings("unchecked")
    private static UUID triggerDevice(java.util.Map<String, Object> evidence) {
        try {
            var frozen = evidence == null ? null : (java.util.Map<String, Object>) evidence.get("frozenContext");
            var trigger = frozen == null ? null : (java.util.Map<String, Object>) frozen.get("trigger");
            Object device = trigger == null ? null : trigger.get("deviceId");
            return device == null ? null : UUID.fromString(device.toString());
        } catch (RuntimeException unreadable) {
            return null;
        }
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
