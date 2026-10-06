package com.nexus.twin.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TwinStateTransitionRepository extends JpaRepository<TwinStateTransition, UUID> {
    @Query("select t from TwinStateTransition t where t.deviceId=:deviceId " +
           "and (:bindingId is null or t.bindingId=:bindingId) " +
           "and (:legacyKey is null or t.legacyCapabilityType=:legacyKey) " +
           "and (:transitionType is null or t.transitionType=:transitionType) " +
           "and (:fromTime is null or t.transitionedAt>=:fromTime) " +
           "and (:toTime is null or t.transitionedAt<=:toTime) " +
           "and (:cursorTime is null or t.transitionedAt<:cursorTime or (t.transitionedAt=:cursorTime and t.transitionId<:cursorId)) " +
           "order by t.transitionedAt desc,t.transitionId desc")
    List<TwinStateTransition> search(@Param("deviceId") UUID deviceId,
            @Param("bindingId") UUID bindingId, @Param("legacyKey") String legacyKey,
            @Param("transitionType") TwinTransitionType transitionType,
            @Param("fromTime") Instant from, @Param("toTime") Instant to,
            @Param("cursorTime") Instant cursorTime, @Param("cursorId") UUID cursorId, Pageable pageable);
}
