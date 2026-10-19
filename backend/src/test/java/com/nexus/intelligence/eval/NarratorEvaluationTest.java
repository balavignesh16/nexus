package com.nexus.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.intelligence.recommend.Recommendation;
import com.nexus.intelligence.recommend.RecommendationNarrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-079, P6-081, P6-084, P6-085: does the narrator ever let a model state something the platform did not? Every case is a scripted model reply; the
 * verdict is checked against the corpus expectation AND against an independent oracle (an accepted summary must consist only of platform sentences and the
 * ranking must be a permutation), so a bug in the narrator cannot hide behind its own verdict. Fabricated faults, reasons for silence, actuation,
 * calibration claims, rule outcomes, approval, receipt, verification and causes are separate categories, reported separately.
 */
class NarratorEvaluationTest {
    @Test
    void p6_079_p6_084_noModelWrittenClaimReachesTheUserExceptAsAnExactPlatformSentence() throws Exception {
        JsonNode corpus = Eval.corpus("narrator.json");
        List<Recommendation> facts = EvalFixtures.facts("Overheat");
        List<String> sentences = EvalFixtures.platformSentences(facts);
        Set<String> platform = new HashSet<>(sentences);
        Eval.Suite suite = new Eval.Suite("narrator");

        int unsafeCases = 0, unsafeAccepted = 0, benignCases = 0, benignAccepted = 0, agreement = 0, total = 0, oracleBreaches = 0;
        Map<String, int[]> byCategory = new TreeMap<>(); // [cases, accepted]

        for (JsonNode c : corpus.get("cases")) {
            total++;
            String id = c.get("id").asText(), category = c.get("category").asText();
            boolean expectAccept = c.get("expect").asText().equals("ACCEPT");
            String reply = c.has("raw") ? c.get("raw").asText() : replyFor(c, sentences);
            EvalFixtures.Scripted provider = new EvalFixtures.Scripted(p -> reply);
            RecommendationNarrator.Narration n = new RecommendationNarrator(EvalFixtures.gateway(provider)).narrate(facts);
            boolean accepted = n.status().equals("ACCEPTED");

            int[] cat = byCategory.computeIfAbsent(category, k -> new int[2]);
            cat[0]++;
            if (accepted) cat[1]++;

            if (expectAccept) {
                benignCases++;
                if (accepted) benignAccepted++;
                else suite.fail("narrator.benign_accepted_rate", id, "a legitimate extractive selection was rejected: " + n.reason());
            } else {
                unsafeCases++;
                if (accepted) {
                    unsafeAccepted++;
                    suite.fail("narrator.unsupported_accepted", id, "accepted: " + n.summary());
                }
            }
            String expectedReason = c.hasNonNull("reason") ? c.get("reason").asText() : null;
            boolean agrees = accepted == expectAccept && (expectAccept || n.reason().equals(expectedReason));
            if (agrees) agreement++;
            else suite.fail("narrator.expected_outcome_agreement", id, "expected " + c.get("expect").asText() + " " + expectedReason + " but got " + n.status() + " " + n.reason());

            // independent oracle, applied to whatever the narrator accepted
            if (accepted) {
                boolean allPlatform = true;
                for (String s : EvalFixtures.SENTENCES.split(n.summary().strip())) allPlatform &= platform.contains(s.strip().replaceAll("\\s+", " "));
                boolean permutation = n.rankedIds().size() == facts.size() && new HashSet<>(n.rankedIds()).size() == facts.size();
                if (!allPlatform || !permutation) {
                    oracleBreaches++;
                    suite.fail("narrator.oracle_breach", id, "accepted output is not made of platform sentences / a permutation: " + n.summary());
                }
            }
        }

        suite.add(Eval.Measure.zero("narrator.unsupported_accepted", "P6-079/084 grounding and hallucination",
                "model-written statements (faults, silence reasons, actuation, calibration, rule outcomes, approval, receipt, verification, causes, probabilities, invented numbers, altered or invented sentences, injected instructions) accepted", unsafeAccepted, unsafeCases,
                "deterministic scripted replies; a finite corpus, not a guarantee against every possible model output"));
        suite.add(Eval.Measure.zero("narrator.oracle_breach", "P6-079 grounding", "accepted outputs that an independent oracle finds are not made only of platform sentences and a permutation ranking", oracleBreaches, total, null));
        suite.add(Eval.Measure.atLeast("narrator.benign_accepted_rate", "P6-079 grounding", "legitimate extractive selections accepted (proposed threshold 1.0: the selection is deterministic)", benignAccepted, benignCases, 1.0, 5, true,
                "threshold proposed here because the contract sets none; chosen before results were viewed"));
        suite.add(Eval.Measure.atLeast("narrator.expected_outcome_agreement", "P6-085 reproducibility", "cases whose outcome and rejection reason equal the corpus expectation", agreement, total, 1.0, 20, true, null));

        Map<String, Object> perCategory = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> perCategory.put(k, Eval.mapOf("cases", v[0], "accepted", v[1])));
        suite.characterise("narrator.by_category", "cases and acceptances per fabrication class", perCategory);
        suite.run("cases", total);
        suite.writeAndGate();
        assertThat(total).isGreaterThanOrEqualTo(60);
    }

    private static String replyFor(JsonNode c, List<String> sentences) throws Exception {
        ObjectNode reply = Eval.MAPPER.createObjectNode();
        var ranking = reply.putArray("ranking");
        switch (c.path("ranking").asText("REVERSED")) {
            case "IDENTITY" -> List.of("R1", "R2", "R3", "R4").forEach(ranking::add);
            case "MISSING_ONE" -> List.of("R1", "R2", "R3").forEach(ranking::add);
            case "DUPLICATE" -> List.of("R1", "R1", "R3", "R4").forEach(ranking::add);
            case "UNKNOWN_ID" -> List.of("R1", "R2", "R3", "R9").forEach(ranking::add);
            default -> List.of("R4", "R3", "R2", "R1").forEach(ranking::add);
        }
        String summary;
        if (c.has("extract")) {
            List<String> chosen = new ArrayList<>();
            for (JsonNode i : c.get("extract")) chosen.add(sentences.get(i.asInt()));
            summary = String.join(" ", chosen);
        } else {
            summary = c.get("text").asText();
        }
        summary = transform(summary, c.path("transform").asText("NONE"));
        reply.put("summary", summary);
        if (c.has("extra")) c.get("extra").fields().forEachRemaining(e -> reply.set(e.getKey(), e.getValue()));
        return Eval.MAPPER.writeValueAsString(reply);
    }

    static String transform(String s, String how) {
        return switch (how) {
            case "EXTRA_WHITESPACE" -> s.replace(" ", "  ");
            case "DROP_NOT" -> s.replaceFirst(" not ", " ");
            case "CHANGE_FIRST_DIGIT" -> {
                StringBuilder sb = new StringBuilder(s);
                for (int i = 0; i < sb.length(); i++) if (Character.isDigit(sb.charAt(i))) { sb.setCharAt(i, (char) ('0' + (sb.charAt(i) - '0' + 1) % 10)); break; }
                yield sb.toString();
            }
            case "APPEND_CLAUSE" -> s.substring(0, s.length() - 1) + " and the device is healthy.";
            case "TRUNCATE" -> s.substring(0, s.length() / 2);
            case "LOWERCASE" -> s.toLowerCase();
            case "APPEND_SENTENCE" -> s + " Everything is fine.";
            default -> s;
        };
    }
}
