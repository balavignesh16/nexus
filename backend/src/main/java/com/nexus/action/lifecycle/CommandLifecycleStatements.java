package com.nexus.action.lifecycle;

import com.nexus.action.lifecycle.CommandLifecycleView.Ack;
import com.nexus.action.lifecycle.CommandLifecycleView.Convergence;
import com.nexus.action.lifecycle.CommandLifecycleView.Delivery;
import com.nexus.rule.ai.ExecutionTruthfulness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The platform-owned sentences that say what the persisted lifecycle facts do and do not prove (ledger P4-114, contract R37). Pure and
 * deterministic: derived only from recorded facts, never from a model, a candidate or a request. Publication, acknowledgement, qualified
 * convergence and physical actuation are stated separately, and an absent fact is never described as having happened. The closing
 * sentence is the shared {@link ExecutionTruthfulness} text, so the wording about physical effect has a single owner.
 */
final class CommandLifecycleStatements {
    private CommandLifecycleStatements() {}

    static List<String> of(String actionType, Delivery delivery, Ack ack, Convergence convergence, boolean ackedAfterTimeout,
                           boolean deliveryUnknown, boolean attemptUncertain) {
        List<String> out = new ArrayList<>();
        if (deliveryUnknown)
            out.add("Delivery outcome unknown: this command was accepted before the reliable outbox existed, so it was neither queued nor replayed and"
                    + " NEXUS cannot say whether it reached the broker.");
        else if (delivery == null)
            out.add("No outbox delivery record exists for this command (it was accepted before the outbox existed or while the outbox was disabled);"
                    + " only the recorded execution status is known.");
        else switch (delivery.state()) {
            case "QUEUED", "RETRY_WAIT" -> out.add("Accepted and waiting to be sent; it has not been confirmed as published to the MQTT broker.");
            case "IN_FLIGHT" -> out.add("A publication attempt is in progress; it has not been confirmed as published to the MQTT broker.");
            case "PUBLISHED" -> out.add("Published to the MQTT broker. Publication does not prove that the device received, accepted or executed the command.");
            case "DEAD_LETTER" -> out.add(deadLetterSentence(delivery.terminalReason(), attemptUncertain));
            default -> out.add("The delivery state is not recognised by this version of NEXUS.");
        }
        if (ack != null) {
            if ("ACCEPTED".equals(ack.status()))
                out.add("The device acknowledged the command. An acknowledgement is the device's own statement and is not proof of physical actuation.");
            else if ("REJECTED".equals(ack.status()))
                out.add("The device acknowledged the command and rejected it.");
            if (ack.timedOut() && ackedAfterTimeout)
                out.add("The ACK deadline passed and the timeout was recorded; the device acknowledged afterwards, and the timeout fact is kept."
                        + " An ACK timeout is not a delivery dead letter.");
            else if (ack.timedOut())
                out.add("No device acknowledgement arrived before the ACK deadline. This is not a delivery dead letter and does not prove that the device"
                        + " did not act.");
            else if ("NONE".equals(ack.status()) && delivery != null && "PUBLISHED".equals(delivery.state()))
                out.add("No device acknowledgement has been recorded yet.");
        }
        if (convergence != null) {
            switch (convergence.status()) {
                case "CONVERGED" -> out.add("A qualified readback matched the desired state (convergence). This is separate from the acknowledgement.");
                case "PENDING" -> out.add("Waiting for a qualified readback of the desired state; none has matched yet.");
                case "DIVERGED" -> out.add("A qualified readback did not match the desired state.");
                case "TIMED_OUT" -> out.add("No qualified readback matched the desired state before the verification deadline.");
                case "UNVERIFIED" -> out.add("This command's effect cannot be verified by a qualified readback.");
                default -> out.add("The verification status is not recognised by this version of NEXUS.");
            }
        }
        String note = ExecutionTruthfulness.noteFor(Set.of(actionType == null ? "" : actionType));
        if (note != null) out.add(note);
        return List.copyOf(out);
    }

    private static String deadLetterSentence(String reason, boolean attemptUncertain) {
        if ("UNCERTAIN_EXPIRED".equals(reason) || (attemptUncertain && reason == null))
            return "Dead-lettered with an uncertain outcome: a publication attempt may already have reached the broker, so the command was not retried and"
                    + " NEXUS cannot say whether it was delivered. The device may still have received it.";
        if ("RETRY_EXHAUSTED".equals(reason))
            return "Dead-lettered: every permitted publication attempt failed before the broker confirmed it, so the command was not published.";
        if ("EXPIRED".equals(reason))
            return "Dead-lettered: the command's short delivery window ended before it could be published, so it was not sent.";
        if ("PAYLOAD_INTEGRITY".equals(reason))
            return "Dead-lettered: the stored command failed its integrity check and was refused, so it was not sent.";
        return "Dead-lettered: delivery ended without a confirmed publication.";
    }
}
