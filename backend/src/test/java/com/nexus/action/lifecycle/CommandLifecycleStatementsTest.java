package com.nexus.action.lifecycle;

import com.nexus.action.lifecycle.CommandLifecycleView.Ack;
import com.nexus.action.lifecycle.CommandLifecycleView.Convergence;
import com.nexus.action.lifecycle.CommandLifecycleView.Delivery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P4-033 (PUBLISHED means broker publication only) and P4-114 (statements stay truthful about the delivery state), at the level of
 * the platform-owned sentences that every lifecycle response carries.
 */
class CommandLifecycleStatementsTest {
    private static final Pattern POSITIVE_FORBIDDEN_CLAIM = Pattern.compile(
            "(?i)(exactly[- ]once|guaranteed|physically (acted|actuated)(?! on)|the device (received|executed|applied) the command(?!\\.? )|was delivered to the device)");

    private static Delivery delivery(String state, String reason) {
        return new Delivery(state, 1, 3, reason, null, null, null, null);
    }

    private static String all(List<String> sentences) { return String.join(" | ", sentences); }

    private static List<List<String>> everyState() {
        List<List<String>> out = new ArrayList<>();
        for (String state : List.of("QUEUED", "IN_FLIGHT", "RETRY_WAIT", "PUBLISHED", "DEAD_LETTER", "SOMETHING_NEW"))
            for (String ack : List.of("NONE", "ACCEPTED", "REJECTED"))
                for (boolean timedOut : List.of(false, true))
                    for (String verification : java.util.Arrays.asList(null, "PENDING", "CONVERGED", "DIVERGED", "TIMED_OUT", "UNVERIFIED"))
                        out.add(CommandLifecycleStatements.of("SEND_COMMAND", delivery(state, state.equals("DEAD_LETTER") ? "UNCERTAIN_EXPIRED" : null),
                                new Ack(ack, timedOut, null, null, null, null), verification == null ? null : new Convergence(verification, null, null), timedOut, false, false));
        return out;
    }

    @Test
    void publishedIsDescribedAsBrokerPublicationOnly() {
        String text = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery("PUBLISHED", null), new Ack("NONE", false, null, null, null, null), null, false, false, false));
        assertTrue(text.contains("Published to the MQTT broker. Publication does not prove that the device received, accepted or executed the command."), text);
        assertTrue(text.contains("No device acknowledgement has been recorded yet"), text);
        // the delivery and acknowledgement sentences (everything before the shared platform note) say nothing about convergence or verification
        String own = String.join(" | ", CommandLifecycleStatements.of("SEND_COMMAND", delivery("PUBLISHED", null), new Ack("NONE", false, null, null, null, null), null,
                false, false, false).subList(0, 2));
        assertFalse(own.toLowerCase().contains("convergence") || own.toLowerCase().contains("verified") || own.toLowerCase().contains("converged"),
                "publication says nothing about convergence: " + own);
    }

    @Test
    void aQueuedOrInFlightCommandNeverClaimsPublicationOrAnAcknowledgement() {
        for (String state : List.of("QUEUED", "IN_FLIGHT", "RETRY_WAIT")) {
            String text = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery(state, null), new Ack("NONE", false, null, null, null, null), null, false, false, false));
            assertTrue(text.contains("has not been confirmed as published"), state + ": " + text);
            assertFalse(text.contains("Published to the MQTT broker."), state + ": " + text);
            assertFalse(text.contains("acknowledged the command"), state + ": " + text);
            assertFalse(text.contains("No device acknowledgement has been recorded yet"), "nothing was published, so nothing is awaited: " + text);
        }
    }

    @Test
    void aDeadLetterNeverClaimsPublicationAndAnUncertainOneNeverClaimsAFailure() {
        String uncertain = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery("DEAD_LETTER", "UNCERTAIN_EXPIRED"), new Ack("NONE", false, null, null, null, null), null, false, false, true));
        assertTrue(uncertain.contains("uncertain outcome") && uncertain.contains("cannot say whether it was delivered"), uncertain);
        assertFalse(uncertain.contains("was not published"), uncertain);
        assertFalse(uncertain.contains("Published to the MQTT broker"), uncertain);
        for (String reason : List.of("RETRY_EXHAUSTED", "EXPIRED", "PAYLOAD_INTEGRITY", "PERMANENT_FAILURE", "NEW_REASON")) {
            String text = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery("DEAD_LETTER", reason), new Ack("NONE", false, null, null, null, null), null, false, false, false));
            assertTrue(text.startsWith("Dead-lettered"), text);
            assertFalse(text.contains("Published to the MQTT broker"), text);
        }
    }

    @Test
    void anAckTimeoutIsNeverDescribedAsADeadLetterOrAsProofThatTheDeviceDidNotAct() {
        String text = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery("PUBLISHED", null), new Ack("NONE", true, null, null, null, null), null, false, false, false));
        assertTrue(text.contains("not a delivery dead letter") && text.contains("does not prove that the device did not act"), text);
        assertFalse(text.contains("Dead-lettered"), text);
    }

    @Test
    void aLegacyCommandWithoutADeliveryRecordIsDeliveryUnknownAndNothingIsInvented() {
        String text = all(CommandLifecycleStatements.of("SEND_COMMAND", null, new Ack("NONE", false, null, null, null, null), null, false, true, false));
        assertTrue(text.startsWith("Delivery outcome unknown"), text);
        assertFalse(text.contains("Published"), text);
        String noFact = all(CommandLifecycleStatements.of("SEND_COMMAND", null, new Ack("NONE", false, null, null, null, null), null, false, false, false));
        assertTrue(noFact.startsWith("No outbox delivery record exists"), noFact);
    }

    @Test
    void convergenceIsAlwaysItsOwnSentenceAndNeverFollowsFromPublicationOrAnAck() {
        String converged = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery("PUBLISHED", null), new Ack("ACCEPTED", false, null, null, null, null), new Convergence("CONVERGED", null, null), false, false, false));
        assertTrue(converged.contains("A qualified readback matched the desired state (convergence). This is separate from the acknowledgement."), converged);
        String none = all(CommandLifecycleStatements.of("SEND_COMMAND", delivery("PUBLISHED", null), new Ack("ACCEPTED", false, null, null, null, null), null, false, false, false));
        assertFalse(none.contains("convergence"), "no verification record, no convergence sentence: " + none);
    }

    @Test
    void noCombinationOfFactsProducesAPhysicalOrExactlyOnceClaimAndEveryResponseEndsWithTheSharedPhysicalCaveat() {
        for (List<String> sentences : everyState()) {
            String text = all(sentences);
            assertFalse(POSITIVE_FORBIDDEN_CLAIM.matcher(text).find(), "forbidden claim in: " + text);
            assertTrue(sentences.get(sentences.size() - 1).endsWith("A recorded status is not evidence, by itself, that a physical action occurred."), text);
            assertEquals(sentences, List.copyOf(sentences));
        }
    }
}
