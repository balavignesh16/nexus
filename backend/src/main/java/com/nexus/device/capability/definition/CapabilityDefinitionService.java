package com.nexus.device.capability.definition;

import com.nexus.device.capability.definition.api.*;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class CapabilityDefinitionService {
    private com.nexus.rule.application.ActiveRuleReferenceGuard activeVersions;
    private org.springframework.jdbc.core.JdbcTemplate locks;
    private jakarta.persistence.EntityManager entities;
    @org.springframework.beans.factory.annotation.Autowired
    void setRuleAuthority(com.nexus.rule.application.ActiveRuleReferenceGuard guard,org.springframework.jdbc.core.JdbcTemplate locks,jakarta.persistence.EntityManager entities){this.activeVersions=guard;this.locks=locks;this.entities=entities;}
    private final CapabilityDefinitionRepository repository;

    public CapabilityDefinitionService(CapabilityDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CapabilityDefinitionResponse> getAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(CapabilityDefinition::getKey))
                .map(CapabilityDefinitionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CapabilityDefinitionResponse get(UUID id) {
        return CapabilityDefinitionResponse.from(find(id));
    }

    @Transactional
    public CapabilityDefinitionResponse create(CapabilityDefinitionRequest request) {
        if (repository.existsByKey(request.key())) {
            throw new IllegalStateException("Capability definition key already exists: " + request.key());
        }
        CapabilityDefinition definition = new CapabilityDefinition(
                UUID.randomUUID(), request.key(), request.displayName(), request.description(),
                request.direction(), request.valueType(), request.unit(), request.allowedUnits(),
                request.minimum(), request.maximum(), request.enumValues(), request.supportedCommands(),
                request.semanticCategory(), request.objectConstraints(), request.metadata());
        return CapabilityDefinitionResponse.from(repository.save(definition));
    }

    @Transactional
    public CapabilityDefinitionResponse update(UUID id, UpdateCapabilityDefinitionRequest request) {
        if(locks!=null){locks.queryForList("SELECT id FROM capability_definitions WHERE id=? FOR UPDATE",id);entities.clear();}
        CapabilityDefinition definition = find(id);
        if(activeVersions!=null && (!java.util.Objects.equals(request.unit(),definition.getUnit())
            || !java.util.Objects.equals(request.allowedUnits(),definition.getAllowedUnits())
            || !java.util.Objects.equals(request.minimum(),definition.getMinimum())
            || !java.util.Objects.equals(request.maximum(),definition.getMaximum())
            || !java.util.Objects.equals(request.enumValues(),definition.getEnumValues())
            || !java.util.Objects.equals(request.supportedCommands(),definition.getSupportedCommands())
            || !java.util.Objects.equals(request.objectConstraints(),definition.getObjectConstraints())))activeVersions.reject(null,definition.getKey());
        definition.update(request.displayName(), request.description(), request.unit(),
                request.allowedUnits(), request.minimum(), request.maximum(), request.enumValues(),
                request.supportedCommands(), request.semanticCategory(),
                request.objectConstraints(), request.metadata());
        return CapabilityDefinitionResponse.from(repository.save(definition));
    }

    private CapabilityDefinition find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapabilityDefinition", id.toString()));
    }
}
