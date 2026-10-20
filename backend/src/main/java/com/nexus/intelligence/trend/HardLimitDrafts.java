package com.nexus.intelligence.trend;

import com.nexus.device.capability.CapabilityType;
import com.nexus.intelligence.draft.DraftOutcome;
import com.nexus.intelligence.draft.DraftService;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The hard-limit rule TEMPLATE: when a capability reaches a limit a PERSON configured, raise an alert and, optionally, send one named command to the same device.
 * It is built from explicit parameters by this code (no model) and handed to the existing draft workflow, which applies the same policy, the Phase 3 validator and the
 * mutual-exclusion analysis, stores it as a DRAFT, and leaves approval to a local approver and activation to the normal lifecycle. Nothing here activates a rule,
 * approves a draft or publishes a command, and it is a software rule that runs on this backend, not a certified hardware emergency cutoff.
 */
@Service
public class HardLimitDrafts {
    public record Request(UUID deviceId, String capabilityKey, CapabilityType capabilityType, Double limit, String unit, HardLimit.Comparison comparison, String cutoffCapability, String cutoffCommand,
                          Boolean cutoffValue, Integer cooldownSeconds) {}

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,99}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,49}");

    private final DraftService drafts;

    public HardLimitDrafts(DraftService drafts) {
        this.drafts = drafts;
    }

    public DraftOutcome propose(Request r) {
        List<String> problems = problems(r);
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        HardLimit.Comparison comparison = r.comparison() == null ? HardLimit.Comparison.GREATER_OR_EQUAL : r.comparison();
        RuleOperator operator = comparison == HardLimit.Comparison.GREATER_OR_EQUAL ? RuleOperator.GTE : RuleOperator.GT;
        String limitText = new java.math.BigDecimal(r.limit()).stripTrailingZeros().toPlainString();
        List<RuleCondition> conditions = List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, r.deviceId().toString()),
                new RuleCondition(RuleField.VALUE, operator, limitText, r.capabilityType(), null, r.unit().strip()));
        List<RuleAction> actions = new ArrayList<>();
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("severity", "WARNING");
        actions.add(new RuleAction("CREATE_ALERT", alert));
        boolean cutoff = r.cutoffCommand() != null;
        if (cutoff) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("deviceId", r.deviceId().toString());
            command.put("capability", r.cutoffCapability());
            command.put("command", r.cutoffCommand());
            command.put("value", r.cutoffValue() != null && r.cutoffValue());
            actions.add(new RuleAction("SEND_COMMAND", command));
        }
        String symbol = comparison == HardLimit.Comparison.GREATER_OR_EQUAL ? ">=" : ">";
        String name = ("Hard limit " + r.capabilityKey() + " " + symbol + " " + limitText + " " + r.unit().strip()).strip();
        if (name.length() > 100) name = name.substring(0, 100);
        String rationale = "Template: when " + r.capabilityKey() + " is " + symbol + " " + limitText + " " + r.unit().strip() + ", raise an alert" + (cutoff ? " and send " + r.cutoffCommand() + " to " + r.cutoffCapability() : "")
                + ". The limit was entered by a person; nothing infers it. This is a software rule on this backend, not a certified hardware emergency cutoff. It is a draft: it does nothing until a local approver approves it and it is activated.";
        String requestKey = String.join("|", "hard-limit", r.deviceId().toString(), r.capabilityKey(), limitText, r.unit().strip(), comparison.name(), String.valueOf(r.cutoffCapability()), String.valueOf(r.cutoffCommand()),
                String.valueOf(r.cutoffValue()));
        return drafts.proposeTemplate(new DraftService.TemplateCandidate("hard-limit", name, 1, r.cooldownSeconds() == null ? 60 : r.cooldownSeconds(), conditions, actions, Set.of(r.deviceId()), requestKey, rationale));
    }

    /** An alert-only hard-limit draft built from a capability key (no cutoff command): the key must name a known capability type. */
    public DraftOutcome proposeAlertLimit(UUID deviceId, String capabilityKey, double limit, String unit) {
        CapabilityType type;
        try {
            type = CapabilityType.valueOf(capabilityKey == null ? "" : capabilityKey.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("the capability " + capabilityKey + " is not a known capability type");
        }
        return propose(new Request(deviceId, capabilityKey, type, limit, unit, HardLimit.Comparison.GREATER_OR_EQUAL, null, null, null, 60));
    }

    static List<String> problems(Request r) {
        List<String> out = new ArrayList<>();
        if (r == null) return List.of("a request is required");
        if (r.deviceId() == null) out.add("deviceId is required");
        if (r.capabilityKey() == null || !KEY.matcher(r.capabilityKey()).matches()) out.add("capabilityKey is not a capability key");
        if (r.capabilityType() == null) out.add("capabilityType is required");
        if (r.limit() == null || !Double.isFinite(r.limit())) out.add("limit must be a finite number the person chose");
        if (r.unit() == null || r.unit().isBlank() || r.unit().length() > 50) out.add("unit is required (1 to 50 characters)");
        boolean anyCutoff = r.cutoffCapability() != null || r.cutoffCommand() != null || r.cutoffValue() != null;
        if (anyCutoff && (r.cutoffCapability() == null || !WORD.matcher(r.cutoffCapability()).matches() || r.cutoffCommand() == null || !WORD.matcher(r.cutoffCommand()).matches() || r.cutoffValue() == null))
            out.add("a cutoff command needs cutoffCapability, cutoffCommand and cutoffValue");
        return out;
    }
}
