package com.nexus.action.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionHistoryRepository extends JpaRepository<ActionHistoryRecord, UUID> {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update ActionHistoryRecord a set a.status = :status, a.timestamp = :at, a.message = :message " +
            "where a.requestId = :id and a.status = com.nexus.action.domain.ExecutionStatus.ACCEPTED_FOR_DISPATCH")
    int updatePublishResultIfAccepted(@Param("id") UUID id, @Param("status") ExecutionStatus status,
            @Param("at") Instant at, @Param("message") String message);

    @Transactional
    @Modifying
    @Query("update ActionHistoryRecord a set a.ackStatus = :status, a.ackReceivedAt = :at, a.ackMessage = :message " +
            "where a.requestId = :id and a.deviceId = :deviceId and a.actionType = com.nexus.action.domain.ActionType.SEND_COMMAND")
    int updateAcknowledgement(@Param("id") UUID id, @Param("deviceId") UUID deviceId,
            @Param("status") AcknowledgementStatus status, @Param("at") Instant at,
            @Param("message") String message);
    Page<ActionHistoryRecord> findAllByOrderByTimestampDesc(Pageable pageable);

    // Device-scoped history (Phase 8): a device's Actions tab must only ever
    // show actions actually recorded against that device.
    Page<ActionHistoryRecord> findByDeviceIdOrderByTimestampDesc(UUID deviceId, Pageable pageable);

    // Tier 4 Phase 1: used by RuleMatchExplanationContextBuilder to retrieve
    // the actions recorded for a given rule match.
    List<ActionHistoryRecord> findByMatchId(UUID matchId);
}
