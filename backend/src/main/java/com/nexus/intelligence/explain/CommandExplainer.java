package com.nexus.intelligence.explain;

import com.nexus.action.lifecycle.CommandLifecycleReader;
import com.nexus.action.lifecycle.CommandLifecycleView;
import com.nexus.action.lifecycle.CommandLifecycleView.Fact;
import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.EvidenceItem;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explains one command from the persisted Phase 4 lifecycle only (the read-only {@link CommandLifecycleReader}): why it was queued, whether and why it
 * was not published, why an attempt became uncertain, why an ACK timed out, why the device did not converge and which pipeline stage failed first.
 * Deterministic and model-free. Publication, acknowledgement, qualified convergence and physical actuation stay separate facts; a fact the platform
 * did not record is UNKNOWN, never a negative. No parameter, payload, topic or device free text is read or shown (the lifecycle view does not carry them).
 */
@Service
public class CommandExplainer {
    public static final String STAGE_NONE = "NONE";
    public static final String STAGE_UNKNOWN = "UNKNOWN";
    public static final String STAGE_DELIVERY = "DELIVERY";
    public static final String STAGE_ACK = "ACK";
    public static final String STAGE_VERIFICATION = "VERIFICATION";

    /** The explanation together with the evidence it was built from (the verifier and the evaluation need both). */
    public record Result(EvidenceBundle bundle, Explanation explanation) {}

    private final CommandLifecycleReader reader;
    private final Clock clock;

    public CommandExplainer(CommandLifecycleReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public Optional<Result> explain(UUID commandId) {
        return reader.find(commandId).map(this::explain);
    }

    /** Pure function of the persisted view: the same facts always give the same explanation (apart from the evaluation instant). */
    public Result explain(CommandLifecycleView v) {
        String p = "command:" + v.commandId() + "/";
        EvidenceBundle b = new EvidenceBundle();
        var d = v.delivery();
        b.add(EvidenceItem.present(p + "execution/status", "recorded execution status of the action", v.executionStatus()));
        b.add(EvidenceItem.presentOrAbsent(p + "match", "rule match that produced the command", v.matchId() == null ? null : v.matchId().toString()));
        if (d == null) {
            for (String leaf : new String[]{"delivery/state", "delivery/acceptedAt", "delivery/attempts", "delivery/terminalReason", "delivery/lastErrorCode", "delivery/firstPublishConfirmedAt"})
                b.add(EvidenceItem.notRecorded(p + leaf, "outbox " + leaf));
        } else {
            b.add(EvidenceItem.present(p + "delivery/state", "outbox delivery state", d.state()));
            b.add(EvidenceItem.presentOrAbsent(p + "delivery/acceptedAt", "when the command was accepted", d.acceptedAt() == null ? null : d.acceptedAt().toString()));
            b.add(EvidenceItem.present(p + "delivery/attempts", "publication attempts made", d.attemptCount()));
            b.add(EvidenceItem.presentOrAbsent(p + "delivery/terminalReason", "terminal reason", d.terminalReason()));
            b.add(EvidenceItem.presentOrAbsent(p + "delivery/lastErrorCode", "last error code", d.lastErrorCode()));
            b.add(EvidenceItem.presentOrAbsent(p + "delivery/firstPublishConfirmedAt", "when publication was first confirmed",
                    d.firstPublishConfirmedAt() == null ? null : d.firstPublishConfirmedAt().toString()));
        }
        var ack = v.ack();
        b.add(EvidenceItem.present(p + "ack/status", "device acknowledgement status", ack.status()));
        b.add(EvidenceItem.present(p + "ack/timedOut", "ACK timeout recorded", ack.timedOut()));
        b.add(EvidenceItem.presentOrAbsent(p + "ack/deadlineAt", "ACK deadline", ack.deadlineAt() == null ? null : ack.deadlineAt().toString()));
        b.add(EvidenceItem.presentOrAbsent(p + "ack/timedOutAt", "when the ACK timeout was recorded", ack.timedOutAt() == null ? null : ack.timedOutAt().toString()));
        var conv = v.convergence();
        if (conv == null) b.add(EvidenceItem.notRecorded(p + "convergence/status", "qualified verification status"));
        else {
            b.add(EvidenceItem.present(p + "convergence/status", "qualified verification status", conv.status()));
            b.add(EvidenceItem.presentOrAbsent(p + "convergence/deadlineAt", "verification deadline", conv.deadlineAt() == null ? null : conv.deadlineAt().toString()));
        }
        for (Fact f : v.facts()) b.add(EvidenceItem.present(p + "fact/" + f.sequence(), "recorded lifecycle fact " + f.sequence(), factValue(f)));

        ExplanationBuilder x = new ExplanationBuilder(b);
        // ---- why it was queued
        if (d == null) {
            x.unknown("No outbox delivery record exists, so when and whether this command was queued is not recorded.");
        } else {
            x.fact("The command was accepted at " + d.acceptedAt() + ".", p + "delivery/acceptedAt");
        }
        if (v.matchId() != null) x.fact("It was produced by rule match " + v.matchId() + ".", p + "match");
        else x.fact("No rule match is recorded for it.", p + "match");
        x.fact("The recorded execution status of the action is " + v.executionStatus() + ".", p + "execution/status");

        // ---- publication, and why not published
        if (d != null) {
            x.fact("The delivery state is " + d.state() + " after " + d.attemptCount() + " publication attempt(s).", p + "delivery/state");
            switch (d.state()) {
                case "PUBLISHED" -> x.fact("Publication was first confirmed at " + d.firstPublishConfirmedAt() + ".", p + "delivery/firstPublishConfirmedAt");
                case "DEAD_LETTER" -> {
                    if (d.terminalReason() != null) x.fact("It ended without a confirmed publication; the recorded terminal reason is " + d.terminalReason() + ".", p + "delivery/terminalReason");
                    else x.unknown("It ended as a dead letter but no terminal reason is recorded.");
                }
                default -> {
                    if (d.firstPublishConfirmedAt() == null) x.fact("It has not been confirmed as published.", p + "delivery/firstPublishConfirmedAt");
                    else {
                        // The record contradicts itself: report both facts and resolve nothing.
                        x.fact("The delivery state is " + d.state() + ", which means not yet published.", p + "delivery/state");
                        x.fact("Publication is nevertheless recorded as first confirmed at " + d.firstPublishConfirmedAt() + ".", p + "delivery/firstPublishConfirmedAt");
                        x.inference("These two recorded facts contradict each other; no resolution is inferred and neither is discarded.", p + "delivery/state",
                                p + "delivery/firstPublishConfirmedAt");
                    }
                }
            }
            if (d.lastErrorCode() != null && !"PUBLISHED".equals(d.state())) x.fact("The last recorded error code is " + d.lastErrorCode() + ".", p + "delivery/lastErrorCode");
        }
        for (Fact f : v.facts()) {
            if ("HELD_UNCERTAIN_PREDECESSOR".equals(f.reason()))
                x.fact("It was held behind an earlier command of the same device whose delivery outcome was uncertain (fact " + f.sequence() + ").", p + "fact/" + f.sequence());
        }
        // ---- uncertain attempts
        for (Fact f : v.facts()) {
            if ("ATTEMPT_UNCERTAIN".equals(f.fact()))
                x.fact("Attempt " + f.attempt() + " ended with an uncertain outcome" + (f.reason() == null ? "" : " (" + f.reason() + ")") + "; the record cannot say whether the broker received it.", p + "fact/" + f.sequence());
        }
        // ---- ACK
        boolean published = d != null && "PUBLISHED".equals(d.state());
        if (ack.timedOut()) x.fact("An ACK timeout was recorded at " + ack.timedOutAt() + " against the deadline " + ack.deadlineAt() + ".", p + "ack/timedOut");
        if ("ACCEPTED".equals(ack.status()) || "REJECTED".equals(ack.status())) {
            x.fact("The device acknowledgement status is " + ack.status() + ".", p + "ack/status");
            for (Fact f : v.facts()) if ("ACK_REJECTED".equals(f.fact()) && f.reason() != null) x.fact("The recorded acknowledgement reason is " + f.reason() + ".", p + "fact/" + f.sequence());
        } else if (published && !ack.timedOut()) {
            x.fact("No device acknowledgement is recorded yet (status " + ack.status() + ").", p + "ack/status");
        }
        // ---- convergence
        if (conv == null) x.unknown("No qualified verification record is linked to this command.");
        else x.fact("The qualified verification status is " + conv.status() + ".", p + "convergence/status");

        // ---- which pipeline stage failed first
        String stage = stage(v);
        List<String> support = new ArrayList<>();
        support.add(p + "execution/status");
        if (d != null) support.add(p + "delivery/state");
        support.add(p + "ack/status");
        support.add(p + "ack/timedOut");
        if (conv != null) support.add(p + "convergence/status");
        switch (stage) {
            case STAGE_UNKNOWN -> x.unknown("Which stage failed cannot be determined: no outbox delivery record exists for this command.");
            case STAGE_NONE -> x.inference("No stage has a failing fact in the recorded evidence.", support.toArray(String[]::new));
            default -> x.inference("The first stage with a failing or uncertain fact is " + stage + ".", support.toArray(String[]::new));
        }
        String summary = d == null ? "Delivery was not recorded for this command; its other recorded facts are listed."
                : "Delivery state " + d.state() + "; acknowledgement " + ack.status() + (ack.timedOut() ? " (timed out)" : "") + "; verification "
                + (conv == null ? "not recorded" : conv.status()) + "; first failing stage: " + stage + ".";
        Explanation e = x.build(ExplanationKind.COMMAND_LIFECYCLE, "command " + v.commandId(), summary, platformStatements(v), false, clock.instant());
        var problems = ExplanationVerifier.verify(b, e);
        if (!problems.isEmpty()) throw new IllegalStateException("the command explanation failed its own grounding check: " + problems);
        return new Result(b, e);
    }

    /** The first stage, in pipeline order, whose persisted fact is failing or uncertain; NONE when none is; UNKNOWN when delivery is not recorded. */
    static String stage(CommandLifecycleView v) {
        var d = v.delivery();
        if (d == null) return STAGE_UNKNOWN;
        switch (d.state()) {
            case "DEAD_LETTER":
                return STAGE_DELIVERY;
            case "PUBLISHED":
                break;
            default:
                return STAGE_NONE;                                 // still queued, retrying or in flight: nothing has failed yet
        }
        var ack = v.ack();
        if (ack.timedOut() && !"ACCEPTED".equals(ack.status())) return STAGE_ACK;
        if ("REJECTED".equals(ack.status())) return STAGE_ACK;
        var c = v.convergence();
        if (c != null && ("DIVERGED".equals(c.status()) || "TIMED_OUT".equals(c.status()))) return STAGE_VERIFICATION;
        return STAGE_NONE;
    }

    private static List<String> platformStatements(CommandLifecycleView v) {
        List<String> out = new ArrayList<>(v.explanation());
        out.add("Delivery evidence kinds in NEXUS are BROKER_CONFIRMED (MQTT), HTTP_SERVED and SIMULATOR_DELIVERED; commands are accepted for MQTT devices only today, so a recorded"
                + " publication means a broker-confirmed publication. None of them means the device received or physically executed the command.");
        return out;
    }

    private static String factValue(Fact f) {
        return f.fact() + "|" + f.fromState() + "|" + f.toState() + "|a" + f.attempt() + "|" + f.reason();
    }
}
