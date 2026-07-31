package com.nexus.rule.api;

import com.nexus.rule.api.dto.RuleRequest;
import com.nexus.rule.api.dto.RuleResponse;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class RuleController {

    private final RuleRegistry ruleRegistry;
    private final BoundedRuleMatchStore matchStore;

    public RuleController(RuleRegistry ruleRegistry, BoundedRuleMatchStore matchStore) {
        this.ruleRegistry = ruleRegistry;
        this.matchStore = matchStore;
    }

    @PostMapping("/rules")
    public ResponseEntity<RuleResponse> createRule(@Valid @RequestBody RuleRequest request) {
        Rule rule = new Rule(
                UUID.randomUUID(),
                request.name(),
                request.enabled(),
                request.priority(),
                request.conditions(),
                request.actions(),
                request.metadata()
        );
        ruleRegistry.register(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(rule));
    }

    @GetMapping("/rules")
    public ResponseEntity<List<RuleResponse>> listRules() {
        List<RuleResponse> rules = ruleRegistry.list().stream()
                .map(RuleResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/rules/{id}")
    public ResponseEntity<RuleResponse> getRule(@PathVariable UUID id) {
        return ruleRegistry.get(id)
                .map(RuleResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("Rule", id.toString()));
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<RuleResponse> updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        if (ruleRegistry.get(id).isEmpty()) {
            throw new com.nexus.shared.exception.ResourceNotFoundException("Rule", id.toString());
        }
        Rule rule = new Rule(
                id,
                request.name(),
                request.enabled(),
                request.priority(),
                request.conditions(),
                request.actions(),
                request.metadata()
        );
        ruleRegistry.register(rule);
        return ResponseEntity.ok(RuleResponse.from(rule));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        ruleRegistry.unregister(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rules/{id}/enable")
    public ResponseEntity<Void> enableRule(@PathVariable UUID id) {
        ruleRegistry.enable(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rules/{id}/disable")
    public ResponseEntity<Void> disableRule(@PathVariable UUID id) {
        ruleRegistry.disable(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/rule-matches")
    public ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchedResponse>> getRuleMatches(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<com.nexus.rule.api.dto.RuleMatchedResponse> responses = matchStore.getRecentMatches(limit).stream()
                .map(com.nexus.rule.api.dto.RuleMatchedResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }
}
