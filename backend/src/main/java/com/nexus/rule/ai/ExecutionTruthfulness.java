package com.nexus.rule.ai;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The platform-owned statement of what an action's recorded status does and does not prove, chosen by the action types actually involved
 * (replacing the blanket "all executors are stubs" sentence, which was false for commands). Pure and deterministic; neither the model nor a
 * candidate can author or change it.
 * <ul>
 * <li>SEND_COMMAND: a real path. Acceptance records a desired-state change; SUCCESS in the action history means the command was PUBLISHED to
 *     the MQTT broker. A device acknowledgement, the device's reported state (convergence) and physical actuation are separate facts, stated
 *     only when independently recorded.</li>
 * <li>CREATE_ALERT and WEBHOOK: stub executors. SUCCESS means the stub ran; no external effect is established.</li>
 * </ul>
 */
public final class ExecutionTruthfulness {
    private ExecutionTruthfulness() {}

    static final String COMMAND_NOTE = "SEND_COMMAND: NEXUS accepts a command by recording a desired-state change; SUCCESS in the action history means the command was published to the MQTT broker. "
        + "A device acknowledgement, a qualified reported readback (verification status, which may be CONVERGED, DIVERGED, TIMED_OUT, PENDING or UNVERIFIED) and any physical actuation are separate facts and are stated only when independently recorded.";
    static final String STUB_NOTE = "CREATE_ALERT and WEBHOOK executors are currently stub implementations: SUCCESS means the stub ran and establishes no external effect.";
    static final String UNKNOWN_TYPE_NOTE = "For an action type not described here, the recorded status is only what the software executor reported.";
    static final String CLOSING = "A recorded status is not evidence, by itself, that a physical action occurred.";

    /** @return the note for these action types, or null when no action is involved at all. */
    public static String noteFor(Collection<String> actionTypes) {
        Set<String> types = new LinkedHashSet<>();
        if (actionTypes != null) for (String type : actionTypes) if (type != null) types.add(type);
        if (types.isEmpty()) return null;
        var parts = new java.util.ArrayList<String>();
        if (types.contains("SEND_COMMAND")) parts.add(COMMAND_NOTE);
        if (types.contains("CREATE_ALERT") || types.contains("WEBHOOK")) parts.add(STUB_NOTE);
        if (types.stream().anyMatch(t -> !Set.of("SEND_COMMAND", "CREATE_ALERT", "WEBHOOK").contains(t))) parts.add(UNKNOWN_TYPE_NOTE);
        parts.add(CLOSING);
        return String.join(" ", parts);
    }

    /** Candidate outcomes that prove an executor was actually invoked for the candidate. */
    private static final Set<String> EXECUTED_OUTCOMES = Set.of("ACCEPTED", "EXECUTED", "EXECUTION_FAILED", "IGNORED", "UNSUPPORTED");

    /**
     * The action types whose executor ACTUALLY ran: the recorded ActionHistory rows plus candidates with an outcome proving an executor was
     * invoked. A suppressed, coalesced, denied or outcome-less candidate is NOT here, so the note never describes an executor that did not run.
     */
    public static Set<String> typesOf(RuleMatchExplanationContext context) {
        Set<String> types = new LinkedHashSet<>();
        context.actions().forEach(a -> types.add(a.actionType()));
        context.candidates().stream().filter(c -> "SELECTED".equals(c.disposition()) && c.outcome() != null && EXECUTED_OUTCOMES.contains(c.outcome()))
            .forEach(c -> types.add(c.actionType()));
        return types;
    }
}
