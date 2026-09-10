package com.nexus.pipeline.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PipelineTraceRepository extends JpaRepository<PipelineTrace, PipelineTraceId> {
    Page<PipelineTrace> findAllByOrderByStartTimestampDesc(Pageable pageable);
    List<PipelineTrace> findByCorrelationIdOrderByStartTimestampAsc(String correlationId);
}
