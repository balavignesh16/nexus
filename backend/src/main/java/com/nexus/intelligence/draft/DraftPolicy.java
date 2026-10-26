package com.nexus.intelligence.draft;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The deterministic safety policy applied to every AI proposal IN ADDITION to the Phase 3 validator (which still judges capabilities, units, operators and
 * device contracts). It only narrows what a model may propose: bounded size, a debounce, no external egress, and a device may be commanded only if it is
 * cited as evidence. Violations are fixed codes; the proposal is then stored INVALID and can never be approved.
 */
public final class DraftPolicy {
    public static final int MAX_CONDITIONS = 6;
    public static final int MAX_ACTIONS = 3;
    public static final int MIN_COOLDOWN_SECONDS = 30;
    public static final int MAX_COOLDOWN_SECONDS = 86_400;
    public static final int MAX_PRIORITY = 1_000;
    public static final Set<String> ALLOWED_ACTIONS = Set.of("SEND_COMMAND", "CREATE_ALERT");

    private DraftPolicy() {}

    public static List<Map<String, Object>> check(Rule rule, Set<UUID> citedDevices) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rule.name() == null || rule.name().isBlank() || rule.name().length() > 100) out.add(v("NAME_LENGTH", "name"));
        if (rule.priority() < 0 || rule.priority() > MAX_PRIORITY) out.add(v("PRIORITY_RANGE", "priority"));
        Integer cooldown = rule.cooldownSeconds();
        if (cooldown == null || cooldown < MIN_COOLDOWN_SECONDS || cooldown > MAX_COOLDOWN_SECONDS) out.add(v("COOLDOWN_RANGE", "cooldownSeconds"));
        if (rule.conditions() == null || rule.conditions().isEmpty() || rule.conditions().size() > MAX_CONDITIONS) out.add(v("CONDITION_COUNT", "conditions"));
        if (rule.actions() == null || rule.actions().isEmpty() || rule.actions().size() > MAX_ACTIONS) out.add(v("ACTION_COUNT", "actions"));
        if (rule.actions() != null) {
            for (int i = 0; i < rule.actions().size(); i++) {
                RuleAction a = rule.actions().get(i);
                String path = "actions[" + i + "]";
                if (a == null || a.actionType() == null || !ALLOWED_ACTIONS.contains(a.actionType())) {
                    out.add(v(a != null && "WEBHOOK".equals(a.actionType()) ? "EXTERNAL_EGRESS_FORBIDDEN" : "ACTION_TYPE_FORBIDDEN", path));
                    continue;
                }
                if ("SEND_COMMAND".equals(a.actionType())) {
                    Object target = a.parameters() == null ? null : a.parameters().get("deviceId");
                    UUID device = parse(target);
                    if (device == null) out.add(v("COMMAND_TARGET_MISSING", path));
                    else if (!citedDevices.contains(device)) out.add(v("COMMAND_TARGET_NOT_CITED", path));
                }
            }
        }
        return out;
    }

    private static UUID parse(Object o) {
        try {
            return o == null ? null : UUID.fromString(o.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> v(String code, String path) {
        return Map.of("code", code, "path", path, "source", "AI_DRAFT_POLICY");
    }
}
