package com.nexus.action.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionHistoryRepository extends JpaRepository<ActionHistoryRecord, UUID> {
    Page<ActionHistoryRecord> findAllByOrderByTimestampDesc(Pageable pageable);

    // Device-scoped history (Phase 8): a device's Actions tab must only ever
    // show actions actually recorded against that device.
    Page<ActionHistoryRecord> findByDeviceIdOrderByTimestampDesc(UUID deviceId, Pageable pageable);

    // Tier 4 Phase 1: used by RuleMatchExplanationContextBuilder to retrieve
    // the actions recorded for a given rule match.
    List<ActionHistoryRecord> findByMatchId(UUID matchId);
}
