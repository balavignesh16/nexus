package com.nexus.rule.api;

import com.nexus.rule.api.dto.RuleMatchDetailResponse;
import com.nexus.rule.api.dto.RuleMatchSummaryResponse;
import com.nexus.rule.api.dto.RuleRequest;
import com.nexus.rule.api.dto.RuleResponse;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.rule.persistence.RuleRegistry;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private static final int MAX_HISTORY_LIMIT = 500;

    private final RuleRegistry ruleRegistry;
    private final BoundedRuleMatchStore matchStore;
    private final RuleMatchRepository ruleMatchRepository;

    public RuleController(RuleRegistry ruleRegistry, BoundedRuleMatchStore matchStore, RuleMatchRepository ruleMatchRepository) {
        this.ruleRegistry = ruleRegistry;
        this.matchStore = matchStore;
        this.ruleMatchRepository = ruleMatchRepository;
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

    /**
     * Optional {@code deviceId} filter: a device's Rules tab must only ever
     * show rules that actually reference that device - never every rule in
     * the system. "Belongs to" is defined as having at least one
     * {@code RuleCondition} with {@code field == DEVICE_ID} and
     * {@code expectedValue} equal to the given deviceId. Rules with no
     * DEVICE_ID condition at all (genuinely platform-wide rules) are
     * correctly excluded from every device-scoped view - filtering happens
     * entirely in-memory against RuleRegistry's already-hydrated rule set,
     * no schema change or query rewrite required.
     */
    @GetMapping("/rules")
    public ResponseEntity<List<RuleResponse>> listRules(
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        List<RuleResponse> rules = ruleRegistry.list().stream()
                .filter(rule -> deviceId == null || belongsToDevice(rule, deviceId))
                .map(RuleResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(rules);
    }

    private boolean belongsToDevice(Rule rule, String deviceId) {
        return rule.conditions().stream().anyMatch(c ->
                c.field() == com.nexus.rule.domain.RuleField.DEVICE_ID && deviceId.equals(c.expectedValue()));
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

    /**
     * Durable rule-match history (Tier 3 Phase 11) - reads RuleMatchRepository,
     * not the volatile BoundedRuleMatchStore above, so results survive a
     * restart. Ordered matchedAt DESC with matchId DESC as a stable
     * tie-break for matches sharing a timestamp.
     */
    @GetMapping("/rule-matches/history")
    public ResponseEntity<List<RuleMatchSummaryResponse>> getRuleMatchHistory(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "ruleId", required = false) UUID ruleId,
            @RequestParam(name = "correlationId", required = false) String correlationId) {
        if (limit <= 0 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_HISTORY_LIMIT);
        }

        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "matchedAt", "matchId"));

        Page<RuleMatchEntity> page;
        if (ruleId != null) {
            page = ruleMatchRepository.findByRuleId(ruleId, pageable);
        } else if (correlationId != null) {
            page = ruleMatchRepository.findByCorrelationId(correlationId, pageable);
        } else {
            page = ruleMatchRepository.findAll(pageable);
        }

        List<RuleMatchSummaryResponse> responses = page.getContent().stream()
                .map(RuleMatchSummaryResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * Durable rule-match detail (Tier 3 Phase 11): the exact rule snapshot
     * and evaluation evidence persisted at match time, independent of any
     * subsequent edit to the live rule.
     */
    @GetMapping("/rule-matches/history/{matchId}")
    public ResponseEntity<RuleMatchDetailResponse> getRuleMatchDetail(@PathVariable UUID matchId) {
        RuleMatchEntity entity = ruleMatchRepository.findById(matchId)
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("RuleMatch", matchId.toString()));
        return ResponseEntity.ok(RuleMatchDetailResponse.from(entity));
    }
}
