package com.nexus.intelligence.api;

import com.nexus.intelligence.explain.CommandExplainer;
import com.nexus.intelligence.explain.Explanation;
import com.nexus.intelligence.explain.RuleNonMatchExplainer;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.rule.domain.TriggerType;
import com.nexus.shared.exception.ResourceNotFoundException;
import com.nexus.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only, deterministic, evidence-cited explanations (Phase 6 Layer 1). GET only: nothing here writes a row, publishes, evaluates for effect or calls a
 * model. Unauthenticated like every NEXUS endpoint today, so it is rate limited with the same limiter as the rule-match explanations; the limiter
 * establishes no identity. Nothing in the response carries command parameters, payloads, topics or device free text.
 */
@RestController
@RequestMapping("/api/v1/intelligence/explanations")
public class ExplanationController {
    private final CommandExplainer commands;
    private final RuleNonMatchExplainer rules;
    private final ExplanationRateLimiter limiter;

    public ExplanationController(CommandExplainer commands, RuleNonMatchExplainer rules, ExplanationRateLimiter limiter) {
        this.commands = commands;
        this.rules = rules;
        this.limiter = limiter;
    }

    @GetMapping("/commands/{commandId}")
    public ResponseEntity<Explanation> command(@PathVariable UUID commandId, HttpServletRequest request) {
        limiter.acquire(request.getRemoteAddr());
        try {
            return ResponseEntity.ok(commands.explain(commandId).orElseThrow(() -> new ResourceNotFoundException("Command", commandId.toString())).explanation());
        } finally {
            limiter.release();
        }
    }

    @GetMapping("/rules/{ruleId}/non-match")
    public ResponseEntity<Explanation> ruleNonMatch(@PathVariable UUID ruleId, @RequestParam(required = false) UUID deviceId,
                                                     @RequestParam(required = false) String trigger, HttpServletRequest request) {
        TriggerType type = null;
        if (trigger != null) {
            try {
                type = TriggerType.valueOf(trigger.trim());
            } catch (IllegalArgumentException unknown) {
                throw new ValidationException("Unknown trigger type");
            }
        }
        limiter.acquire(request.getRemoteAddr());
        try {
            return ResponseEntity.ok(rules.explain(ruleId, deviceId, type).orElseThrow(() -> new ResourceNotFoundException("Rule", ruleId.toString())).explanation());
        } finally {
            limiter.release();
        }
    }

    /** "What if" with ONE assumed capability value, answered only by the deterministic dry-run and labelled hypothetical. */
    @GetMapping("/rules/{ruleId}/what-if")
    public ResponseEntity<Explanation> whatIf(@PathVariable UUID ruleId, @RequestParam UUID deviceId, @RequestParam String capabilityKey, @RequestParam String valueType,
                                              @RequestParam String value, @RequestParam(required = false) String unit, @RequestParam(required = false) String trigger,
                                              HttpServletRequest request) {
        TriggerType type = trigger == null ? null : parseTrigger(trigger);
        JsonNode node = switch (valueType) {
            case "NUMBER" -> {
                try {
                    yield JsonNodeFactory.instance.numberNode(new BigDecimal(value));
                } catch (NumberFormatException bad) {
                    throw new ValidationException("value is not a number");
                }
            }
            case "BOOLEAN" -> {
                if (!value.equals("true") && !value.equals("false")) throw new ValidationException("value is not a boolean");
                yield JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(value));
            }
            case "STRING" -> JsonNodeFactory.instance.textNode(value);
            default -> throw new ValidationException("valueType must be NUMBER, BOOLEAN or STRING");
        };
        if (capabilityKey.length() > 64 || value.length() > 256 || (unit != null && unit.length() > 32)) throw new ValidationException("input too long");
        limiter.acquire(request.getRemoteAddr());
        try {
            return ResponseEntity.ok(rules.whatIf(ruleId, deviceId, type, new RuleNonMatchExplainer.WhatIf(capabilityKey, valueType, node, unit))
                    .orElseThrow(() -> new ResourceNotFoundException("Rule", ruleId.toString())).explanation());
        } finally {
            limiter.release();
        }
    }

    private static TriggerType parseTrigger(String trigger) {
        try {
            return TriggerType.valueOf(trigger.trim());
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException("Unknown trigger type");
        }
    }
}
