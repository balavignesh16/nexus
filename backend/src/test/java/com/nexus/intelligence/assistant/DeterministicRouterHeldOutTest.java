package com.nexus.intelligence.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.assistant.DeterministicRouter.Context;
import com.nexus.intelligence.assistant.DeterministicRouter.Kind;
import com.nexus.intelligence.assistant.DeterministicRouter.Plan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic router on the 30 routing questions of the frozen held-out corpus (the same questions the local model was measured on). NOT an independent test of
 * the router: the corpus and the router have the same author, and the router was written knowing the kinds of question in it, so its score here is a regression
 * check and a comparison point, not an estimate of how it will do on other people's questions. The SAFETY facts are asserted: no write request and no hostile question
 * is ever routed to a read of something it did not name; the accuracy figures are printed and recorded.
 */
class DeterministicRouterHeldOutTest {
    private static final Path HELDOUT = Path.of("..", "architecture", "phase6", "evaluation", "heldout", "p6-heldout-v1.json");
    private static final List<String> NAMES = List.of("Greenhouse sensor 2", "Boiler room fan", "Pump 3", "Server rack humidity", "Boiler 1", "Garage door", "Compressor sensor");

    @Test
    void p6_181_everyWriteAndOutOfScopeQuestionOfTheFrozenCorpusIsRefusedAndTheAccuracyIsRecorded() throws Exception {
        JsonNode corpus = new ObjectMapper().readTree(new String(Files.readAllBytes(HELDOUT), StandardCharsets.UTF_8));
        int correct = 0, total = 0, refusalCases = 0, refusalCorrect = 0, unsafe = 0;
        List<String> misses = new ArrayList<>();
        for (JsonNode c : corpus.get("routing")) {
            total++;
            String id = c.get("id").asText();
            Plan plan = DeterministicRouter.route(c.get("question").asText(), NAMES, Context.EMPTY);
            JsonNode expect = c.get("expect");
            boolean ok;
            if (expect.has("refusal")) {
                refusalCases++;
                boolean refused = expect.get("refusal").asText().equals("WRITE_REQUEST") ? plan.kind() == Kind.WRITE : (plan.kind() == Kind.SCOPE || plan.kind() == Kind.WRITE);
                ok = refused && plan.calls().isEmpty();
                if (ok) refusalCorrect++;
                if (!plan.calls().isEmpty()) unsafe++;
            } else {
                Set<String> got = new TreeSet<>();
                plan.calls().forEach(call -> got.add(call.trend() ? "TREND" : call.request().type().name()));
                Set<String> want = new TreeSet<>();
                expect.get("types").forEach(t -> want.add(t.asText()));
                ok = plan.kind() == Kind.TOOLS && got.equals(want);
                if (ok && expect.has("deviceName")) ok = plan.calls().stream().allMatch(call -> expect.get("deviceName").asText().equalsIgnoreCase(call.request().deviceName()));
                if (ok && expect.has("ruleId")) ok = plan.calls().stream().anyMatch(call -> call.request().ruleId() != null && call.request().ruleId().toString().equals(expect.get("ruleId").asText()));
                if (ok && expect.has("commandId")) ok = plan.calls().stream().anyMatch(call -> call.request().commandId() != null && call.request().commandId().toString().equals(expect.get("commandId").asText()));
                if (ok && expect.has("capabilityKey")) ok = plan.calls().stream().anyMatch(call -> expect.get("capabilityKey").asText().equals(call.request().capabilityKey()));
                if (ok && expect.has("lookbackHours")) ok = plan.calls().stream().anyMatch(call -> call.windowHours() != null && call.windowHours() == expect.get("lookbackHours").asInt());
                // a hostile question must not produce a read of something other than what it plainly asked
                if (expect.path("hostile").asBoolean(false) && plan.calls().stream().anyMatch(call -> call.request().deviceName() != null && !call.request().deviceName().equals(expect.path("deviceName").asText(null)))) unsafe++;
            }
            if (ok) correct++;
            else misses.add(id + " -> " + plan.kind() + " " + plan.calls().stream().map(call -> call.trend() ? "TREND" : call.request().type().name()).toList());
        }
        System.out.println("DETERMINISTIC ROUTER on the frozen corpus: correct " + correct + " of " + total + "; refusals " + refusalCorrect + " of " + refusalCases + "; misses " + misses);
        assertThat(total).isEqualTo(30);
        assertThat(unsafe).as("no refused question and no hostile question produced a read it did not ask for").isZero();
        assertThat(refusalCorrect).as("every write and out-of-scope question was refused").isEqualTo(refusalCases);
    }
}
