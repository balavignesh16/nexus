package com.nexus.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The scoring primitives every suite relies on: a wrong verdict here would make every report wrong, so they are tested on their own. */
class EvalSupportTest {
    @Test
    void p6_086_aZeroMeasureIsOnlyEverAPassAtZeroWithCases() {
        assertThat(Eval.Measure.zero("a", "d", "x", 0, 5, null).verdict()).isEqualTo(Eval.Verdict.PASS);
        assertThat(Eval.Measure.zero("a", "d", "x", 1, 5, null).verdict()).isEqualTo(Eval.Verdict.FAIL);
        assertThat(Eval.Measure.zero("a", "d", "x", 0, 0, null).verdict()).as("no cases is not a pass").isEqualTo(Eval.Verdict.NOT_EVALUATED);
        assertThat(Eval.Measure.notEvaluated("a", "d", "x", "why").verdict()).isEqualTo(Eval.Verdict.NOT_EVALUATED);
    }

    @Test
    void p6_086_rateMeasuresCompareInclusivelyAndFewCasesAreInconclusive() {
        assertThat(Eval.Measure.atLeast("a", "d", "x", 9, 10, 0.9, 5, true, null).verdict()).isEqualTo(Eval.Verdict.PASS);
        assertThat(Eval.Measure.atLeast("a", "d", "x", 8, 10, 0.9, 5, true, null).verdict()).isEqualTo(Eval.Verdict.FAIL);
        assertThat(Eval.Measure.atMost("a", "d", "x", 1, 20, 0.05, 5, true, null).verdict()).isEqualTo(Eval.Verdict.PASS);
        assertThat(Eval.Measure.atMost("a", "d", "x", 2, 20, 0.05, 5, true, null).verdict()).isEqualTo(Eval.Verdict.FAIL);
        assertThat(Eval.Measure.atLeast("a", "d", "x", 3, 3, 0.9, 10, true, null).verdict()).as("3 cases cannot support a verdict").isEqualTo(Eval.Verdict.INCONCLUSIVE);
    }

    @Test
    void p6_086_onlyGatedFailuresBlockASuite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path ignored) {
        Eval.Suite suite = new Eval.Suite("unit");
        suite.add(Eval.Measure.zero("blocking", "d", "x", 2, 5, null));
        suite.add(Eval.Measure.atMost("informational", "d", "x", 9, 10, 0.05, 5, false, null));
        suite.add(Eval.Measure.atLeast("fine", "d", "x", 10, 10, 0.9, 5, true, null));
        assertThat(suite.blockingFailures()).hasSize(1).first().asString().startsWith("blocking");
        Eval.Suite onlyInformational = new Eval.Suite("unit2");
        onlyInformational.add(Eval.Measure.atMost("informational", "d", "x", 9, 10, 0.05, 5, false, null));
        assertThat(onlyInformational.blockingFailures()).isEmpty();
        Eval.Suite unevaluated = new Eval.Suite("unit3");
        unevaluated.add(Eval.Measure.notEvaluated("n", "d", "x", "not run"));
        assertThat(unevaluated.blockingFailures()).as("an unevaluated measure is not a failure, and is not a pass either").isEmpty();
        assertThat(unevaluated.toJson().get("measures").get(0).get("verdict").asText()).isEqualTo("NOT_EVALUATED");
    }

    @Test
    void p6_072_theResultFileHasNoTimestampSoTwoRunsOfTheSameCorpusAreIdentical() throws Exception {
        Eval.Suite a = new Eval.Suite("repro").add(Eval.Measure.zero("m", "d", "x", 0, 3, null));
        Eval.Suite b = new Eval.Suite("repro").add(Eval.Measure.zero("m", "d", "x", 0, 3, null));
        assertThat(Eval.MAPPER.writeValueAsString(a.toJson())).isEqualTo(Eval.MAPPER.writeValueAsString(b.toJson()));
        assertThat(a.toJson().toString()).doesNotContainIgnoringCase("timestamp").doesNotContain("2026-");
        assertThat(a.toJson().get("run").get("corpusVersion").asText()).isEqualTo(Eval.CORPUS_VERSION);
        assertThat(a.toJson().get("run").get("provider").asText()).isEqualTo("scripted-offline");
    }

    @Test
    void p6_072_aCorpusFileIsHashedOverNormalisedLineEndingsAndTheLoaderRefusesAnUnlistedOrAlteredFile() {
        assertThat(Eval.sha256(Eval.normalise("a\r\nb".getBytes(StandardCharsets.UTF_8)))).isEqualTo(Eval.sha256("a\nb".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> Eval.corpus("not-in-the-manifest.json")).isInstanceOf(IllegalStateException.class);
        JsonNode narrator = Eval.corpus("narrator.json");
        assertThat(narrator.get("cases").size()).isGreaterThanOrEqualTo(60);
    }
}
