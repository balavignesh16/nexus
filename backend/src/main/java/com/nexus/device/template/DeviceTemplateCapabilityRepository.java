package com.nexus.device.template;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DeviceTemplateCapabilityRepository extends JpaRepository<DeviceTemplateCapability,UUID> {
 List<DeviceTemplateCapability> findByTemplateIdOrderByDisplayOrder(UUID id); boolean existsByTemplateIdAndDefinitionId(UUID templateId,UUID definitionId);
}
