package com.nexus.twin.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TemplateReadbackMappingRepository extends JpaRepository<TemplateReadbackMapping,UUID> {
    List<TemplateReadbackMapping> findByTemplateId(UUID templateId);
}
