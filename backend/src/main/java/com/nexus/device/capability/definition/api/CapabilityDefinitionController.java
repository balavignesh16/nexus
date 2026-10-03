package com.nexus.device.capability.definition.api;

import com.nexus.device.capability.definition.CapabilityDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/capability-definitions")
public class CapabilityDefinitionController {
    private final CapabilityDefinitionService service;
    public CapabilityDefinitionController(CapabilityDefinitionService service) { this.service = service; }

    @GetMapping
    public List<CapabilityDefinitionResponse> getAll() { return service.getAll(); }

    @GetMapping("/{id}")
    public CapabilityDefinitionResponse get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CapabilityDefinitionResponse create(@Valid @RequestBody CapabilityDefinitionRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public CapabilityDefinitionResponse update(@PathVariable UUID id,
            @Valid @RequestBody UpdateCapabilityDefinitionRequest request) {
        return service.update(id, request);
    }
}
