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
        CapabilityDefinition definition = find(id);
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
