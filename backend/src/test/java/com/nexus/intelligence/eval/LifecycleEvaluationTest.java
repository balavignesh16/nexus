package com.nexus.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.action.lifecycle.CommandLifecycleView;
import com.nexus.action.lifecycle.CommandLifecycleView.Ack;
import com.nexus.action.lifecycle.CommandLifecycleView.Convergence;
import com.nexus.action.lifecycle.CommandLifecycleView.Delivery;
import com.nexus.action.lifecycle.CommandLifecycleView.Fact;
import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.Presence;
import com.nexus.intelligence.explain.CommandExplainer;
import com.nexus.intelligence.explain.Explanation;
import com.nexus.intelligence.explain.ExplanationVerifier;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;
import com.nexus.rule.ai.UnsupportedAssertionGuard;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-075, P6-076, P6-077, P6-079, P6-080, P6-081: deterministic command explanations over a corpus of lifecycle views (queued, retrying, published,
 * acknowledged, acknowledged WITHOUT verified state, converged, diverged, timed out, dead-lettered, expired, uncertain, contradictory, missing). Required and
 * forbidden statements come from what each view's own fields can support; independent oracles (not the verifier) check that nothing is fabricated.
 */
class LifecycleEvaluationTest {
    static final Instant T0 = Instant.parse("2026-10-18T10:00:00Z");
    static final UUID ID = UUID.fromString("a1b2c3d4-e5f6-4789-8abc-def012345678");
    static final UUID MATCH = UUID.fromString("b1b2c3d4-e5f6-4789-8abc-def012345678");
    private final CommandExplainer explainer = new CommandExplainer(null, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));

    private CommandLifecycleView view(JsonNode v) {
        Delivery delivery = null;
        if (!v.get("delivery").isNull()) {
            JsonNode d = v.get("delivery");
            String state = d.get("state").asText();
            boolean published = d.path("published").asBoolean(false);
            delivery = new Delivery(state, d.get("attempts").asInt(), 5, d.hasNonNull("terminalReason") ? d.get("terminalReason").asText() : null,
                    d.hasNonNull("errorCode") ? d.get("errorCode").asText() : null, T0, published ? T0.plusSeconds(3) : null,
                    state.equals("DEAD_LETTER") || state.equals("PUBLISHED") || state.equals("DELIVERY_UNKNOWN") ? T0.plusSeconds(3) : null);
        }
        JsonNode a = v.get("ack");
        String ackStatus = a.get("status").asText();
        boolean timedOut = a.path("timedOut").asBoolean(false);
        Ack ack = new Ack(ackStatus, timedOut, T0.plusSeconds(60), "NONE".equals(ackStatus) ? null : T0.plusSeconds(5), timedOut ? T0.plusSeconds(61) : null, null);
        Convergence convergence = v.get("convergence").isNull() ? null : new Convergence(v.get("convergence").asText(), T0.plusSeconds(10), null);
        List<Fact> facts = new ArrayList<>();
        int n = 1;
        facts.add(new Fact(n++, "ACCEPTED", null, null, 0, null, "ACCEPTANCE", T0));
        if (delivery != null) facts.add(new Fact(n++, "QUEUED", null, "QUEUED", 0, null, "ACCEPTANCE", T0.plusSeconds(1)));
        if (v.has("facts")) for (JsonNode f : v.get("facts")) facts.add(new Fact(n++, f.get("fact").asText(), null, null, 1, f.hasNonNull("reason") ? f.get("reason").asText() : null, "DISPATCHER", T0.plusSeconds(n)));
        return new CommandLifecycleView(ID, UUID.randomUUID(), MATCH, "SEND_COMMAND", delivery != null && "PUBLISHED".equals(delivery.state()) ? "SUCCESS" : "ACCEPTED_FOR_DISPATCH", delivery, ack, convergence,
                new CommandLifecycleView.Latency(null, null, null, null), List.of("A recorded status is not evidence, by itself, that a physical action occurred."), facts, false);
    }

    @Test
    void p6_075_p6_076_p6_077_p6_079_p6_080_p6_081_everyLifecycleStateIsExplainedFromItsFactsWithoutFabricatingDeliveryAcknowledgementOrActuation() throws Exception {
        JsonNode corpus = Eval.corpus("lifecycle.json");
        Eval.Suite suite = new Eval.Suite("lifecycle");
        int views = 0, factClaims = 0, factSupported = 0, refs = 0, refsResolved = 0, reasonOk = 0, uncertainViews = 0, uncertainViolations = 0, fabricationChecks = 0, fabrications = 0,
                guardChecks = 0, guardViolations = 0, publishedViews = 0, separationOk = 0, missingEvidenceViews = 0, abstainedOrUnknown = 0, verifierProblems = 0;
        Map<String, Object> perCategory = new LinkedHashMap<>();

        for (JsonNode v : corpus.get("views")) {
            views++;
            String id = v.get("id").asText(), category = v.get("category").asText();
            CommandLifecycleView lifecycle = view(v);
            CommandExplainer.Result result = explainer.explain(lifecycle);
            Explanation e = result.explanation();
            EvidenceBundle bundle = result.bundle();
            List<String> claimTexts = e.claims().stream().map(Claim::text).toList();
            String all = String.join("\n", claimTexts) + "\n" + String.join("\n", e.platformStatements());

            // independent grounding oracle
            for (Claim c : e.claims()) {
                List<String> cited = new ArrayList<>();
                if (c.tier() == ClaimTier.FACT && c.evidenceRef() != null) cited.add(c.evidenceRef());
                cited.addAll(c.supportingEvidenceRefs());
                for (String r : cited) { refs++; if (bundle.resolve(r).isPresent()) refsResolved++; else suite.fail("lifecycle.citation_resolution", id, "unresolvable " + r); }
                if (c.tier() == ClaimTier.FACT) {
                    factClaims++;
                    var item = bundle.resolve(c.evidenceRef());
                    // PRESENT supports exactly its value; ABSENT (a persisted absence) supports exactly the claim that there is no value; NOT_RECORDED supports nothing
                    if (item.isPresent() && item.get().presence() != Presence.NOT_RECORDED && String.valueOf(item.get().value()).equals(String.valueOf(c.assertedValue()))) factSupported++;
                    else suite.fail("lifecycle.claim_support", id, "FACT not supported by its evidence: " + c.text());
                }
            }
            verifierProblems += ExplanationVerifier.verify(bundle, e).size();

            // required and forbidden statements
            boolean ok = true;
            for (JsonNode r : v.get("require")) if (!Pattern.compile(r.asText(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(all).find()) { ok = false; suite.fail("lifecycle.correct_reason", id, "missing required statement /" + r.asText() + "/"); }
            List<String> forbidHits = new ArrayList<>();
            for (JsonNode f : v.get("forbid")) if (Pattern.compile(f.asText(), Pattern.CASE_INSENSITIVE).matcher(String.join("\n", claimTexts)).find()) { ok = false; forbidHits.add(f.asText()); suite.fail("lifecycle.correct_reason", id, "forbidden statement /" + f.asText() + "/"); }
            if (ok) reasonOk++;
            if (category.startsWith("UNCERTAIN")) { uncertainViews++; if (!forbidHits.isEmpty()) { uncertainViolations++; suite.fail("lifecycle.uncertain_reported_as_success", id, forbidHits.toString()); } }
            if (v.path("expectUnknownClaim").asBoolean(false) || v.get("convergence").isNull()) {
                missingEvidenceViews++;
                if (e.claims().stream().anyMatch(c -> c.tier() == ClaimTier.UNKNOWN) || e.abstained()) abstainedOrUnknown++;
                else suite.fail("lifecycle.abstention_missing_evidence", id, "missing evidence was not marked UNKNOWN");
            }

            // oracle: nothing is stated that the view's fields do not record
            boolean published = !v.get("delivery").isNull() && v.get("delivery").path("published").asBoolean(false);
            boolean ackRecorded = !v.get("ack").get("status").asText().equals("NONE");
            fabricationChecks += 3;
            if (!published && Pattern.compile("publication was first confirmed", Pattern.CASE_INSENSITIVE).matcher(all).find()) { fabrications++; suite.fail("lifecycle.fabricated_delivery_or_ack_evidence", id, "states a confirmed publication that is not recorded"); }
            if (!ackRecorded && Pattern.compile("acknowledgement status is (ACCEPTED|REJECTED)", Pattern.CASE_INSENSITIVE).matcher(all).find()) { fabrications++; suite.fail("lifecycle.fabricated_delivery_or_ack_evidence", id, "states an acknowledgement that is not recorded"); }
            if (v.get("convergence").isNull() && Pattern.compile("verification status is", Pattern.CASE_INSENSITIVE).matcher(String.join("\n", claimTexts)).find()) { fabrications++; suite.fail("lifecycle.fabricated_delivery_or_ack_evidence", id, "states a verification status that is not recorded"); }

            // oracle: no actuation / receipt / approval / fault assertion anywhere
            for (String text : concat(claimTexts, e.platformStatements())) { guardChecks++; var violation = UnsupportedAssertionGuard.violation(text); if (violation != null) { guardViolations++; suite.fail("lifecycle.asserted_actuation_receipt_approval_or_fault", id, violation + ": " + text); } }

            // delivery, acknowledgement, convergence and actuation stay separate
            if (!v.get("delivery").isNull() && v.get("delivery").get("state").asText().equals("PUBLISHED")) {
                publishedViews++;
                if (String.join(" ", e.platformStatements()).contains("None of them means the device received or physically executed the command")) separationOk++;
                else suite.fail("lifecycle.stage_separation", id, "the platform statement separating publication from receipt and execution is missing");
            }
            perCategory.put(id + " " + category, ok ? "as expected" : "MISMATCH");
        }

        suite.add(Eval.Measure.atLeast("lifecycle.citation_resolution", "P6-079 grounding", "cited references that resolve to the evidence bundle (contract: 100 percent)", refsResolved, refs, 1.0, 20, true, "contract threshold"));
        suite.add(Eval.Measure.atLeast("lifecycle.claim_support", "P6-079 grounding", "FACT claims whose cited item is PRESENT and holds the asserted value (contract: at least 0.98)", factSupported, factClaims, 0.98, 50, true, "contract threshold"));
        suite.add(Eval.Measure.zero("lifecycle.verifier_problems", "P6-079 grounding", "problems reported by ExplanationVerifier over the corpus", verifierProblems, views, null));
        suite.add(Eval.Measure.atLeast("lifecycle.correct_reason", "P6-080 correct reason", "views whose explanation makes every required statement and no forbidden one (contract: at least 0.98; deterministic fixtures expected 1.0)", reasonOk, views, 0.98, 20, true, "contract threshold"));
        suite.add(Eval.Measure.zero("lifecycle.uncertain_reported_as_success", "P6-076 uncertain delivery", "uncertain-delivery views explained as published, delivered or successful", uncertainViolations, uncertainViews, null));
        suite.add(Eval.Measure.zero("lifecycle.fabricated_delivery_or_ack_evidence", "P6-075 no fabricated publication or ACK", "statements of a publication, acknowledgement or verification that the view does not record", fabrications, fabricationChecks, null));
        suite.add(Eval.Measure.zero("lifecycle.asserted_actuation_receipt_approval_or_fault", "P6-077 no claim of physical actuation", "claim or platform sentences asserting actuation, receipt or execution, approval or a fault", guardViolations, guardChecks,
                "the guard is a closed word list; the sentences here are deterministic platform text, so this checks the platform's own wording stays inside the safe vocabulary"));
        suite.add(Eval.Measure.atLeast("lifecycle.stage_separation", "P6-077 delivery, ACK, convergence and actuation are separate", "published views that carry the platform statement that publication does not mean the device received or executed the command", separationOk, publishedViews, 1.0, 5, true, null));
        suite.add(Eval.Measure.atLeast("lifecycle.abstention_missing_evidence", "P6-081 abstention", "views with missing evidence (no outbox record, or no qualified verification) that mark it UNKNOWN or abstain (contract: 100 percent)", abstainedOrUnknown, missingEvidenceViews, 1.0, 5, true, "contract threshold"));
        suite.characterise("lifecycle.views", "views by category", perCategory);
        suite.run("views", views).run("realRows", "not part of this suite: the same explainer over persisted rows is exercised by CommandExplanationIntegrationTest and FailureRecommendationIntegrationTest");
        suite.writeAndGate();
        assertThat(views).isGreaterThanOrEqualTo(20);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
