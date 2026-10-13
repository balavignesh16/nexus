package com.nexus.rule.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Durable record of a single rule-match outcome (Tier 3 Phase 9A). A
 * separate persistence-only JPA mapping, exactly mirroring why {@link
 * RuleEntity} is kept separate from {@code Rule}: {@code RuleMatchedEvent}
 * is a Java record and is deliberately left untouched as a pure domain
 * type - JPA concerns are confined entirely to this class.
 *
 * Phase 9A establishes only the schema/entity/repository foundation.
 * Nothing in the running RuleMatcher writes to this table yet - that wiring
 * is explicitly deferred to Phase 9B.
 */
@Entity
@Table(name = "rule_matches")
public class RuleMatchEntity {
    @Column(name="cooldown_eligible",nullable=false) private boolean cooldownEligible=true;
    public void setCooldownEligible(boolean eligible){this.cooldownEligible=eligible;}
    public boolean isCooldownEligible(){return cooldownEligible;}
    @Column(name="rule_version_id") private UUID ruleVersionId;
    @Column(name="rule_revision") private Long ruleRevision;
    @Column(name="execution_epoch") private Long executionEpoch;
    public void attribute(com.nexus.rule.domain.RuleExecutionIdentity identity) {
        if(identity!=null) { ruleVersionId=identity.ruleVersionId();ruleRevision=identity.ruleRevision();executionEpoch=identity.executionEpoch(); }
    }
    public UUID getRuleVersionId(){return ruleVersionId;}
    public Long getRuleRevision(){return ruleRevision;}
    public Long getExecutionEpoch(){return executionEpoch;}

    @Id
    @Column(name = "match_id")
    private UUID matchId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> ruleSnapshot;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "correlation_id")
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evaluationEvidence;

    @Column(name = "matched_at", nullable = false)
    private Instant matchedAt;

    @Column(name = "message")
    private String message;

    protected RuleMatchEntity() {
    }

    public RuleMatchEntity(UUID matchId, UUID ruleId, Map<String, Object> ruleSnapshot, UUID eventId,
                            String correlationId, Map<String, Object> evaluationEvidence, Instant matchedAt,
                            String message) {
        this.matchId = matchId;
        this.ruleId = ruleId;
        this.ruleSnapshot = ruleSnapshot;
        this.eventId = eventId;
        this.correlationId = correlationId;
        this.evaluationEvidence = evaluationEvidence;
        this.matchedAt = matchedAt;
        this.message = message;
    }

    public UUID getMatchId() {
        return matchId;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public Map<String, Object> getRuleSnapshot() {
        return ruleSnapshot;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getEvaluationEvidence() {
        return evaluationEvidence;
    }

    public Instant getMatchedAt() {
        return matchedAt;
    }

    public String getMessage() {
        return message;
    }
}
