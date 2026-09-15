package com.nexus.rule.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RuleMatchRepository extends JpaRepository<RuleMatchEntity, UUID> {
    Page<RuleMatchEntity> findByRuleId(UUID ruleId, Pageable pageable);
    Page<RuleMatchEntity> findByCorrelationId(String correlationId, Pageable pageable);

    /**
     * Phase 13: one row per rule that has ever matched, with its most
     * recent matchedAt - used exactly once, at RuleMatcher startup, to
     * rebuild in-memory cooldown state from the durable source of truth.
     * Never called per-event (see RuleMatcher.hydrateCooldownState).
     */
    @Query("SELECT rm.ruleId, MAX(rm.matchedAt) FROM RuleMatchEntity rm GROUP BY rm.ruleId")
    List<Object[]> findLastMatchedAtByRule();

    /**
     * Device-scoped rule-match history (Phase 3). RuleMatchEntity has no
     * deviceId column of its own - this uses the real, already-persisted
     * eventId FK to DomainEvent (the event that triggered the match) via a
     * subquery, never an invented/duplicated deviceId field on this entity.
     */
    @Query("SELECT rm FROM RuleMatchEntity rm WHERE rm.eventId IN "
            + "(SELECT e.eventId FROM DomainEvent e WHERE e.deviceId = :deviceId)")
    Page<RuleMatchEntity> findByDeviceId(@Param("deviceId") UUID deviceId, Pageable pageable);
}
