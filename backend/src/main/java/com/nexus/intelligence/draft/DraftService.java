package com.nexus.intelligence.draft;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.draft.store.DraftStore;
import com.nexus.intelligence.model.DataBlock;
import com.nexus.intelligence.model.ModelGateway;
import com.nexus.intelligence.model.ModelOutcome;
import com.nexus.intelligence.model.ModelPurpose;
import com.nexus.intelligence.model.ModelRequest;
import com.nexus.intelligence.model.StructuredOutput;
import com.nexus.rule.application.RuleCapabilityValidator;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.persistence.RuleVersionStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a natural-language goal into a STORED PROPOSAL, never into a rule. The model sees the goal (user text) and a minimised device catalogue (device
 * evidence, so it needs the separate consent) and must answer with one strict JSON object. The platform then converts it with a strict mapper, requires every
 * cited device to be one it showed the model, applies {@link DraftPolicy}, runs the Phase 3 validator, adds overlap warnings and stores the result. This
 * class has no way to approve, activate or publish: it holds no rule writer, no outbox and no connection to an approval path.
 */
@Service
public class DraftService {
    private static final Pattern DEVICE_REF = Pattern.compile("device:([0-9a-fA-F-]{36})");
    /** Changes whenever the instruction text or the output schema changes, so a stored draft says which template produced it. */
    static final String PROMPT_TEMPLATE = "draft-rule/1";
    static final int CATALOGUE_LIMIT = 50;
    static final int MIN_GOAL = 8;
    static final int MAX_GOAL = 500;

    static final StructuredOutput.Schema SCHEMA = StructuredOutput.Schema.of(Map.of(
            "name", StructuredOutput.FieldType.STRING, "priority", StructuredOutput.FieldType.NUMBER, "cooldownSeconds", StructuredOutput.FieldType.NUMBER,
            "conditions", StructuredOutput.FieldType.OBJECT_LIST, "actions", StructuredOutput.FieldType.OBJECT_LIST,
            "evidenceRefs", StructuredOutput.FieldType.STRING_LIST, "rationale", StructuredOutput.FieldType.STRING),
            Set.of("name", "conditions", "actions", "evidenceRefs", "cooldownSeconds"));

    private final ModelGateway gateway;
    private final DraftStore store;
    private final RuleCapabilityValidator validator;
    private final RuleVersionStore rules;
    private final ObjectMapper mapper;
    private final ObjectMapper strict;
    private final Clock clock;

    public DraftService(ModelGateway gateway, DraftStore store, RuleCapabilityValidator validator, RuleVersionStore rules, ObjectMapper mapper, Clock clock) {
        this.gateway = gateway;
        this.store = store;
        this.validator = validator;
        this.rules = rules;
        this.mapper = mapper;
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = clock;
    }

    public DraftOutcome propose(String goalText) {
        String goal = goalText == null ? "" : goalText.strip();
        if (goal.length() < MIN_GOAL || goal.length() > MAX_GOAL || goal.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) return new DraftOutcome.Refused(DraftRefusal.GOAL_INVALID, null);
        List<DraftStore.Catalogue> catalogue = store.catalogue(CATALOGUE_LIMIT);
        if (catalogue.isEmpty()) return new DraftOutcome.Refused(DraftRefusal.NO_DEVICES, null);

        ModelOutcome outcome = gateway.ask(new ModelRequest(ModelPurpose.DRAFT_RULE, instruction(), List.of(
                DataBlock.userText("goal", goal), DataBlock.evidence("device_catalogue", catalogueText(catalogue))), SCHEMA));
        if (outcome instanceof ModelOutcome.Refused refused) return new DraftOutcome.Refused(DraftRefusal.MODEL_REFUSED, refused.code());
        ModelOutcome.Completed completed = (ModelOutcome.Completed) outcome;

        Candidate candidate;
        try {
            candidate = candidate(completed.values());
        } catch (RuntimeException malformed) {
            return new DraftOutcome.Refused(DraftRefusal.MALFORMED_CANDIDATE, null);
        }
        if (candidate.refs().isEmpty()) return new DraftOutcome.Refused(DraftRefusal.MISSING_EVIDENCE, null);
        Set<UUID> shown = new LinkedHashSet<>();
        catalogue.forEach(c -> shown.add(c.id()));
        Set<UUID> cited = new LinkedHashSet<>();
        for (String ref : candidate.refs()) {
            Matcher m = DEVICE_REF.matcher(ref);
            UUID id = null;
            if (m.matches()) {
                try {
                    id = UUID.fromString(m.group(1));
                } catch (IllegalArgumentException ignored) { /* unresolved below */ }
            }
            if (id == null || !shown.contains(id)) return new DraftOutcome.Refused(DraftRefusal.UNRESOLVED_EVIDENCE, null);
            cited.add(id);
        }

        UUID draftId = UUID.randomUUID(), ruleId = UUID.randomUUID();
        Rule rule = new Rule(ruleId, candidate.name(), false, candidate.priority(), candidate.conditions(), candidate.actions(),
                Map.of("origin", "AI_DRAFT", "draftId", draftId.toString()), candidate.cooldown(), null, null);

        List<Map<String, Object>> policy = DraftPolicy.check(rule, cited);
        List<Map<String, Object>> diagnostics;
        try {
            diagnostics = validator.inspect(rule);
        } catch (RuntimeException unavailable) {
            return new DraftOutcome.Refused(DraftRefusal.VALIDATOR_UNAVAILABLE, null);
        }
        List<Map<String, Object>> warnings = overlaps(rule);
        List<Map<String, Object>> all = new ArrayList<>(policy);
        all.addAll(diagnostics);
        boolean valid = all.isEmpty();

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("valid", valid);
        validation.put("policyViolations", policy);
        validation.put("validatorDiagnostics", diagnostics);
        validation.put("warnings", warnings);
        validation.put("validatedAt", clock.instant().toString());
        validation.put("validator", "RuleCapabilityValidator+DraftPolicy");
        validation.put("promptTemplate", PROMPT_TEMPLATE);
        // The rationale is model prose shown to the approver. If it asserts actuation, receipt, approval, a fault or a cause it is not stored at all.
        String rationale = candidate.rationale();
        if (rationale != null && com.nexus.rule.ai.UnsupportedAssertionGuard.violation(rationale) != null) {
            validation.put("rationaleDropped", com.nexus.rule.ai.UnsupportedAssertionGuard.violation(rationale).name());
            rationale = null;
        }
        String definition = write(rule);
        List<String> refs = new ArrayList<>(cited.stream().map(id -> "device:" + id).toList());
        String hash = store.insert(draftId, valid, definition, completed.identity(), sha256(goal), rationale, refs, write(validation));
        if (valid) store.supersede(draftId);
        return new DraftOutcome.Created(draftId, valid ? "PENDING_APPROVAL" : "INVALID", valid, hash, all, warnings);
    }

    private record Candidate(String name, int priority, Integer cooldown, List<RuleCondition> conditions, List<RuleAction> actions, List<String> refs, String rationale) {}

    @SuppressWarnings("unchecked")
    private Candidate candidate(Map<String, Object> v) {
        List<RuleCondition> conditions = new ArrayList<>();
        for (Object o : (List<Object>) v.get("conditions")) conditions.add(strict.convertValue(o, RuleCondition.class));
        List<RuleAction> actions = new ArrayList<>();
        for (Object o : (List<Object>) v.get("actions")) actions.add(strict.convertValue(o, RuleAction.class));
        Number cooldown = (Number) v.get("cooldownSeconds");
        Number priority = (Number) v.getOrDefault("priority", 10);
        if (cooldown.doubleValue() != Math.rint(cooldown.doubleValue()) || priority.doubleValue() != Math.rint(priority.doubleValue())) throw new IllegalArgumentException("not integral");
        List<String> refs = new ArrayList<>((List<String>) v.get("evidenceRefs"));
        String rationale = v.get("rationale") instanceof String s ? s.substring(0, Math.min(s.length(), 1000)) : null;
        return new Candidate((String) v.get("name"), priority.intValue(), cooldown.intValue(), conditions, actions, refs, rationale);
    }

    /** Active rules that command the same target as the draft: reported, never blocking (a human decides). */
    private List<Map<String, Object>> overlaps(Rule draft) {
        Set<String> targets = new LinkedHashSet<>();
        for (RuleAction a : draft.actions()) target(a).ifPresent(targets::add);
        if (targets.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (RuleVersionStore.View active : rules.list(true)) {
                for (RuleAction a : active.definition().actions()) {
                    if (target(a).filter(targets::contains).isPresent()) {
                        out.add(Map.of("code", "OVERLAPS_ACTIVE_RULE", "ruleId", active.ruleId().toString(), "target", target(a).get()));
                        break;
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            out.add(Map.of("code", "OVERLAP_CHECK_UNAVAILABLE"));
        }
        return out;
    }

    private static java.util.Optional<String> target(RuleAction a) {
        if (a == null || !"SEND_COMMAND".equals(a.actionType()) || a.parameters() == null || a.parameters().get("deviceId") == null) return java.util.Optional.empty();
        Object capability = a.parameters().get("capabilityKey") != null ? a.parameters().get("capabilityKey") : a.parameters().get("capability");
        return java.util.Optional.of(a.parameters().get("deviceId") + "/" + capability);
    }

    private String catalogueText(List<DraftStore.Catalogue> catalogue) {
        StringBuilder sb = new StringBuilder();
        for (DraftStore.Catalogue c : catalogue) sb.append("device:").append(c.id()).append(' ').append(c.type()).append(" [").append(String.join(", ", c.capabilities())).append("]\n");
        return sb.toString();
    }

    private static String instruction() {
        return "You propose ONE rule for a building-automation platform. You cannot run, enable or approve anything: a person reviews your proposal.\n"
                + "Answer with ONE JSON object and nothing else, with exactly these fields: name (string, at most 100 characters), priority (integer 0 to 1000), cooldownSeconds (integer 30 to 86400),"
                + " conditions (1 to 6 objects with field one of EVENT_TYPE, DEVICE_ID, SENSOR_TYPE, VALUE, SEVERITY; operator one of EQ, NE, GT, LT, GTE, LTE; expectedValue string; optional capabilityType, capabilityKey, unit),"
                + " actions (1 to 3 objects with actionType SEND_COMMAND or CREATE_ALERT and a parameters object; SEND_COMMAND needs deviceId, and command and value for the capability),"
                + " evidenceRefs (the device:<id> entries of the catalogue you used; at least one) and rationale (string).\n"
                + "Use only devices and capabilities that appear in the device_catalogue block. If the goal cannot be met with them, answer with the same object using the closest safe alert-only rule.";
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("draft not serialisable", e);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
