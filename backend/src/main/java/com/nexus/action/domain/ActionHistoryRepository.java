package com.nexus.action.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ActionHistoryRepository extends JpaRepository<ActionHistoryRecord, UUID> {
    Page<ActionHistoryRecord> findAllByOrderByTimestampDesc(Pageable pageable);
}
