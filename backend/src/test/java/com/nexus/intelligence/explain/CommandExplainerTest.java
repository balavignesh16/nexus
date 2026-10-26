package com.nexus.intelligence.explain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.action.lifecycle.CommandLifecycleView;
import com.nexus.action.lifecycle.CommandLifecycleView.Ack;
import com.nexus.action.lifecycle.CommandLifecycleView.Convergence;
import com.nexus.action.lifecycle.CommandLifecycleView.Delivery;
import com.nexus.action.lifecycle.CommandLifecycleView.Fact;
import com.nexus.intelligence.evidence.Presence;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command explainer over synthetic lifecycle views (no database): every scenario of ledger P6-025 to P6-032, P6-034, P6-035, P6-031 and P6-037 that does
 * not need persisted rows. The persisted-row versions are in CommandExplanationIntegrationTest.
 */
class CommandExplainerTest {
    private static final Instant T0 = Instant.parse("2026-10-18T10:00:00Z");
    private static final UUID ID = UUID.fromString("a1b2c3d4-e5f6-4789-8abc-def012345678");
    private static final UUID MATCH = UUID.fromString("b1b2c3d4-e5f6-4789-8abc-def012345678");
    private final CommandExplainer explainer = new CommandExplainer(null, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));

    // ------------------------------------------------------------------------------------------ builders

    private static Delivery delivery(String state, int attempts, String terminal, String error, Instant firstPublished) {
        return new Delivery(state, attempts, 5, terminal, error, T0, firstPublished, "DEAD_LETTER".equals(state) || "PUBLISHED".equals(state) ? T0.plusSeconds(3) : null);
    }

    private static Ack ack(String status, boolean timedOut) {
        return new Ack(status, timedOut, T0.plusSeconds(60), "NONE".equals(status) ? null : T0.plusSeconds(5), timedOut ? T0.plusSeconds(61) : null, null);
    }

    private static Fact fact(int n, String fact, String reason, int attempt) {
        return new Fact(n, fact, null, null, attempt, reason, "DISPATCHER", T0.plusSeconds(n));
    }

    private static CommandLifecycleView view(Delivery d, Ack a, Convergence c, Fact... facts) {
        return new CommandLifecycleView(ID, UUID.randomUUID(), MATCH, "SEND_COMMAND", d != null && "PUBLISHED".equals(d.state()) ? "SUCCESS" : "ACCEPTED_FOR_DISPATCH", d, a, c,
                new CommandLifecycleView.Latency(null, null, null, null), List.of("Platform sentence one.", "A recorded status is not evidence, by itself, that a physical action occurred."),
                List.of(facts), false);
    }

    private CommandExplainer.Result run(CommandLifecycleView v) {
        return explainer.explain(v);
    }

    private static List<String> texts(Explanation e, ClaimTier tier) {
        return e.claims().stream().filter(c -> c.tier() == tier).map(Claim::text).collect(Collectors.toList());
    }

    private static boolean has(Explanation e, String fragment) {
        return e.claims().stream().anyMatch(c -> c.text().contains(fragment));
    }

    // ------------------------------------------------------------------------------------------ queued, not published, uncertain

    @Test
    void p6_025_aQueuedCommandIsExplainedFromItsAcceptanceFacts() {
        var e = run(view(delivery("QUEUED", 0, null, null, null), ack("NONE", false), null, fact(1, "ACCEPTED", null, 0), fact(2, "QUEUED", null, 0))).explanation();
        assertTrue(has(e, "was accepted at " + T0));
        assertTrue(has(e, "produced by rule match " + MATCH));
        assertTrue(has(e, "delivery state is QUEUED after 0 publication attempt(s)"));
        assertTrue(has(e, "has not been confirmed as published"));
        assertEquals(CommandExplainer.STAGE_NONE, CommandExplainer.stage(view(delivery("QUEUED", 0, null, null, null), ack("NONE", false), null)));
        assertFalse(has(e, "No device acknowledgement"), "an unpublished command is not described as awaiting an acknowledgement");
    }

    @Test
    void p6_026_aCommandThatWasNotPublishedIsExplainedByItsRecordedReason() {
        var exhausted = run(view(delivery("DEAD_LETTER", 5, "RETRY_EXHAUSTED", "BROKER_DISCONNECTED", null), ack("NONE", false), null)).explanation();
        assertTrue(has(exhausted, "terminal reason is RETRY_EXHAUSTED"));
        assertTrue(has(exhausted, "last recorded error code is BROKER_DISCONNECTED"));
        var expired = run(view(delivery("DEAD_LETTER", 0, "EXPIRED", null, null), ack("NONE", false), null)).explanation();
        assertTrue(has(expired, "terminal reason is EXPIRED"));
        var held = run(view(delivery("QUEUED", 0, null, null, null), ack("NONE", false), null, fact(3, "QUEUED", "HELD_UNCERTAIN_PREDECESSOR", 0))).explanation();
        assertTrue(has(held, "held behind an earlier command of the same device whose delivery outcome was uncertain"));
        // a dead letter whose reason was not recorded says so instead of guessing
        var unrecorded = run(view(delivery("DEAD_LETTER", 5, null, null, null), ack("NONE", false), null)).explanation();
        assertTrue(texts(unrecorded, ClaimTier.UNKNOWN).stream().anyMatch(t -> t.contains("no terminal reason is recorded")));
        assertTrue(texts(unrecorded, ClaimTier.FACT).stream().noneMatch(t -> t.contains("terminal reason is")), "no reason is asserted as a fact");
    }

    @Test
    void p6_027_anUncertainAttemptIsExplainedAsUncertainAndNeverAsFailureOrSuccess() {
        var e = run(view(delivery("DEAD_LETTER", 1, "UNCERTAIN_EXPIRED", "PUBLISH_TIMEOUT", null), ack("NONE", false), null, fact(2, "ATTEMPT_UNCERTAIN", "PUBLISH_TIMEOUT", 1))).explanation();
        assertTrue(has(e, "Attempt 1 ended with an uncertain outcome (PUBLISH_TIMEOUT); the record cannot say whether the broker received it."));
        assertTrue(has(e, "terminal reason is UNCERTAIN_EXPIRED"));
        assertFalse(texts(e, ClaimTier.FACT).stream().anyMatch(t -> t.contains("failed to") || t.contains("succeeded") || t.contains("was delivered")));
        assertEquals(CommandExplainer.STAGE_DELIVERY, CommandExplainer.stage(view(delivery("DEAD_LETTER", 1, "UNCERTAIN_EXPIRED", null, null), ack("NONE", false), null)));
    }

    @Test
    void p6_028_anAckTimeoutIsExplainedWithItsDeadlineAndIsNotADeliveryFailure() {
        var v = view(delivery("PUBLISHED", 1, null, null, T0.plusSeconds(2)), ack("NONE", true), null, fact(4, "ACK_TIMED_OUT", null, 1));
        var e = run(v).explanation();
        assertTrue(has(e, "An ACK timeout was recorded at " + T0.plusSeconds(61) + " against the deadline " + T0.plusSeconds(60)));
        assertTrue(has(e, "Publication was first confirmed at " + T0.plusSeconds(2)));
        assertFalse(has(e, "No device acknowledgement is recorded yet"), "after the timeout the ACK is explained by the timeout fact");
        assertEquals(CommandExplainer.STAGE_ACK, CommandExplainer.stage(v));
        assertFalse(has(e, "device failed") || has(e, "did not act"), "absence of an ACK is never described as a device failure");
    }

    @Test
    void p6_029_everyVerificationStatusIsExplainedSeparatelyFromDeliveryAndAck() {
        for (String status : new String[]{"CONVERGED", "PENDING", "DIVERGED", "TIMED_OUT", "UNVERIFIED"}) {
            var v = view(delivery("PUBLISHED", 1, null, null, T0.plusSeconds(2)), ack("ACCEPTED", false), new Convergence(status, T0.plusSeconds(9), T0.plusSeconds(90)));
            var e = run(v).explanation();
            assertTrue(has(e, "qualified verification status is " + status), status);
            boolean failing = status.equals("DIVERGED") || status.equals("TIMED_OUT");
            assertEquals(failing ? CommandExplainer.STAGE_VERIFICATION : CommandExplainer.STAGE_NONE, CommandExplainer.stage(v), status);
            assertTrue(has(e, "acknowledgement status is ACCEPTED"), "the ACK is its own statement: " + status);
            assertFalse(has(e, "actuat"), "no claim about physical actuation: " + status);
        }
        var none = run(view(delivery("PUBLISHED", 1, null, null, T0), ack("ACCEPTED", false), null)).explanation();
        assertTrue(texts(none, ClaimTier.UNKNOWN).stream().anyMatch(t -> t.contains("No qualified verification record")));
    }

    @Test
    void p6_030_theFirstFailingStageIsFoundInPipelineOrder() {
        var published = delivery("PUBLISHED", 1, null, null, T0);
        assertEquals(CommandExplainer.STAGE_DELIVERY, CommandExplainer.stage(view(delivery("DEAD_LETTER", 5, "RETRY_EXHAUSTED", null, null), ack("NONE", false), null)));
        assertEquals(CommandExplainer.STAGE_ACK, CommandExplainer.stage(view(published, ack("NONE", true), new Convergence("DIVERGED", T0, T0))), "an earlier stage wins over a later one");
        assertEquals(CommandExplainer.STAGE_ACK, CommandExplainer.stage(view(published, ack("REJECTED", false), null)));
        assertEquals(CommandExplainer.STAGE_VERIFICATION, CommandExplainer.stage(view(published, ack("ACCEPTED", false), new Convergence("TIMED_OUT", T0, T0))));
        assertEquals(CommandExplainer.STAGE_NONE, CommandExplainer.stage(view(published, ack("ACCEPTED", false), new Convergence("CONVERGED", T0, T0))));
        assertEquals(CommandExplainer.STAGE_NONE, CommandExplainer.stage(view(delivery("RETRY_WAIT", 2, null, "BROKER_DISCONNECTED", null), ack("NONE", false), null)), "retrying is not a failure yet");
        assertEquals(CommandExplainer.STAGE_UNKNOWN, CommandExplainer.stage(view(null, ack("NONE", false), null)));
        var late = view(published, ack("ACCEPTED", true), null);       // ACKed after the timeout: the ACK arrived, so the ACK stage did not fail
        assertEquals(CommandExplainer.STAGE_NONE, CommandExplainer.stage(late));
        var none = run(view(published, ack("ACCEPTED", false), new Convergence("CONVERGED", T0, T0))).explanation();
        assertTrue(none.claims().stream().anyMatch(c -> c.tier() == ClaimTier.INFERENCE && c.text().startsWith("No stage has a failing fact")));
        var failed = run(view(published, ack("NONE", true), null)).explanation();
        assertTrue(failed.claims().stream().anyMatch(c -> c.tier() == ClaimTier.INFERENCE && c.text().contains("is ACK")), "the stage is an INFERENCE citing its support, not a fact");
    }

    // ------------------------------------------------------------------------------------------ unknown, contradiction, abstention

    @Test
    void p6_018_p6_034_missingDeliveryEvidenceIsUnknownNeverNegative() {
        var r = run(view(null, ack("NONE", false), null));
        var e = r.explanation();
        assertTrue(r.bundle().items().stream().filter(i -> i.ref().contains("/delivery/")).allMatch(i -> i.presence() == Presence.NOT_RECORDED));
        assertTrue(texts(e, ClaimTier.UNKNOWN).stream().anyMatch(t -> t.contains("when and whether this command was queued is not recorded")));
        assertTrue(texts(e, ClaimTier.UNKNOWN).stream().anyMatch(t -> t.contains("Which stage failed cannot be determined")));
        assertFalse(texts(e, ClaimTier.FACT).stream().anyMatch(t -> t.contains("not published") || t.contains("not been confirmed") || t.contains("queued")),
                "no negative statement about a record that does not exist");
        assertEquals(List.of(), ExplanationVerifier.verify(r.bundle(), e));
    }

    @Test
    void p6_032_contradictoryRecordedFactsAreBothShownAndNothingIsResolved() {
        var contradictory = view(delivery("QUEUED", 1, null, null, T0.plusSeconds(2)), ack("NONE", false), null);
        var e = run(contradictory).explanation();
        assertTrue(has(e, "which means not yet published"));
        assertTrue(has(e, "Publication is nevertheless recorded as first confirmed at " + T0.plusSeconds(2)));
        assertTrue(e.claims().stream().anyMatch(c -> c.tier() == ClaimTier.INFERENCE && c.text().contains("contradict each other; no resolution is inferred")));
        assertFalse(has(e, "has not been confirmed as published"));
    }

    @Test
    void p6_035_theExplanationIsDeterministicForTheSameFacts() throws Exception {
        var v = view(delivery("PUBLISHED", 1, null, null, T0.plusSeconds(2)), ack("ACCEPTED", false), new Convergence("PENDING", T0, T0.plusSeconds(90)), fact(1, "ACCEPTED", null, 0), fact(2, "PUBLISHED", null, 1));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertEquals(mapper.writeValueAsString(run(v).explanation()), mapper.writeValueAsString(run(v).explanation()));
        assertEquals(run(v).explanation(), run(v).explanation());
    }

    // ------------------------------------------------------------------------------------------ wording and exposure

    private static final Pattern FORBIDDEN = Pattern.compile("(?i)(received by the device|device received|was executed|physically|actuated|exactly[- ]once|guaranteed)");

    @Test
    void p6_031_noClaimStatesReceiptActuationOrExactlyOnceAndTheEvidenceKindsAreNamed() {
        List<CommandLifecycleView> matrix = new ArrayList<>();
        for (String state : new String[]{"QUEUED", "RETRY_WAIT", "IN_FLIGHT", "PUBLISHED", "DEAD_LETTER"})
            for (String ackStatus : new String[]{"NONE", "ACCEPTED", "REJECTED"})
                for (boolean timedOut : new boolean[]{false, true})
                    for (Convergence c : new Convergence[]{null, new Convergence("CONVERGED", T0, T0), new Convergence("DIVERGED", T0, T0), new Convergence("PENDING", T0, T0)})
                        matrix.add(view(delivery(state, 1, state.equals("DEAD_LETTER") ? "UNCERTAIN_EXPIRED" : null, null, state.equals("PUBLISHED") ? T0 : null), ack(ackStatus, timedOut), c,
                                fact(1, "ACCEPTED", null, 0)));
        matrix.add(view(null, ack("NONE", false), null));
        assertTrue(matrix.size() > 100);
        for (var v : matrix) {
            var r = run(v);
            for (Claim c : r.explanation().claims()) assertFalse(FORBIDDEN.matcher(c.text()).find(), c.text());
            assertFalse(FORBIDDEN.matcher(r.explanation().summary()).find(), r.explanation().summary());
            assertEquals(List.of(), ExplanationVerifier.verify(r.bundle(), r.explanation()));
            String statements = String.join(" ", r.explanation().platformStatements());
            assertTrue(statements.contains("BROKER_CONFIRMED") && statements.contains("HTTP_SERVED") && statements.contains("SIMULATOR_DELIVERED"));
            assertTrue(statements.contains("None of them means the device received or physically executed the command."));
        }
    }

    @Test
    void p6_037_nothingAboutParametersPayloadsTopicsOrDeviceTextIsExposed() throws Exception {
        var v = view(delivery("PUBLISHED", 1, null, null, T0), ack("REJECTED", false), null, fact(5, "ACK_REJECTED", "DEVICE_REASON_OTHER", 1));
        var r = run(v);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(r.explanation()).toLowerCase();
        for (String forbidden : new String[]{"payload", "parameters", "topic", "nexus/devices", "password", "secret"}) assertFalse(json.contains(forbidden), forbidden);
        assertTrue(r.bundle().items().stream().allMatch(i -> i.ref().startsWith("command:" + ID + "/")));
        assertTrue(has(r.explanation(), "recorded acknowledgement reason is DEVICE_REASON_OTHER"), "a device-chosen reason arrives already reduced by the lifecycle view");
    }
}
