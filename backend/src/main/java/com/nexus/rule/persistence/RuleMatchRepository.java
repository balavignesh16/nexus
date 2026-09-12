package com.nexus.rule.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RuleMatchRepository extends JpaRepository<RuleMatchEntity, UUID> {
    Page<RuleMatchEntity> findByRuleId(UUID ruleId, Pageable pageable);
    Page<RuleMatchEntity> findByCorrelationId(String correlationId, Pageable pageable);
}
