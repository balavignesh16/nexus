package com.nexus.rule.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RuleMatchRepository extends JpaRepository<RuleMatchEntity, UUID> {
}
