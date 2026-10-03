package com.nexus.device.capability.definition;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CapabilityDefinitionRepository extends JpaRepository<CapabilityDefinition, UUID> {
    Optional<CapabilityDefinition> findByKey(String key);
    boolean existsByKey(String key);
}
