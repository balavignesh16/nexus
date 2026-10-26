package com.nexus.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The evaluation harness shared by every P6.5 suite. A suite measures a finite corpus and writes a result file; the release gate (tests/p6_eval_gate.py)
 * reads the files. Nothing here is a model: the default provider of every suite is a scripted, deterministic one, so two runs give identical results.
 *
 * <p>Verdicts, never silently merged:
 * PASS = the measured criterion is met; FAIL = it is violated; NOT_EVALUATED = the evaluation did not run or has no cases; INCONCLUSIVE = it ran on too few
 * cases to support a verdict. An abstention or an unexecuted test is never turned into a pass.
 */
public final class Eval {
    private Eval() {}

    public static final String CORPUS_VERSION = "p6-eval-corpus-v1";
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(SerializationFeature.INDENT_OUTPUT);

    public enum Verdict { PASS, FAIL, NOT_EVALUATED, INCONCLUSIVE }

    /** ZERO: the observed count of violations must be 0. AT_LEAST / AT_MOST compare a rate with the threshold. */
    public enum Kind { ZERO, AT_LEAST, AT_MOST }

    /**
     * One measured criterion. {@code n} is the number of cases behind {@code observed}; with fewer than {@code minN} the verdict is INCONCLUSIVE. A measure with
     * {@code gated=false} is a characterisation against a proposed bound: its verdict is reported but does not block the gate.
     */
    public record Measure(String id, String dimension, String description, Kind kind, double observed, double threshold, int n, int minN, boolean gated, String note) {
        public Verdict verdict() {
            if (n <= 0) return Verdict.NOT_EVALUATED;
            if (n < minN) return Verdict.INCONCLUSIVE;
            boolean ok = switch (kind) {
                case ZERO -> observed == 0.0;
                case AT_LEAST -> observed >= threshold;
                case AT_MOST -> observed <= threshold;
            };
            return ok ? Verdict.PASS : Verdict.FAIL;
        }

        public static Measure zero(String id, String dimension, String description, int violations, int cases, String note) {
            return new Measure(id, dimension, description, Kind.ZERO, violations, 0, cases, 1, true, note);
        }

        public static Measure atLeast(String id, String dimension, String description, int hits, int cases, double threshold, int minN, boolean gated, String note) {
            return new Measure(id, dimension, description, Kind.AT_LEAST, cases == 0 ? 0 : hits / (double) cases, threshold, cases, minN, gated, note);
        }

        public static Measure atMost(String id, String dimension, String description, int hits, int cases, double threshold, int minN, boolean gated, String note) {
            return new Measure(id, dimension, description, Kind.AT_MOST, cases == 0 ? 0 : hits / (double) cases, threshold, cases, minN, gated, note);
        }

        public static Measure notEvaluated(String id, String dimension, String description, String reason) {
            return new Measure(id, dimension, description, Kind.ZERO, 0, 0, 0, 1, true, reason);
        }
    }

    /** A reported figure with no threshold (a characterisation of behaviour, for example a per-family rate or a latency percentile). */
    public record Characterisation(String id, String description, Map<String, Object> values) {}

    /** A case that failed its expectation or produced a violation, kept so the report can list it. */
    public record CaseFailure(String measureId, String caseId, String detail) {}

    public static final class Suite {
        private final String name;
        private final List<Measure> measures = new ArrayList<>();
        private final List<Characterisation> characterisations = new ArrayList<>();
        private final List<CaseFailure> failures = new ArrayList<>();
        private final Map<String, Object> run = new TreeMap<>();

        public Suite(String name) {
            this.name = name;
            run.put("corpusVersion", CORPUS_VERSION);
            run.put("provider", "scripted-offline");
            run.put("providerKind", "deterministic test double; no model was called");
        }

        public Suite add(Measure m) {
            measures.add(m);
            return this;
        }

        public Suite characterise(String id, String description, Map<String, Object> values) {
            characterisations.add(new Characterisation(id, description, new TreeMap<>(values)));
            return this;
        }

        public Suite fail(String measureId, String caseId, String detail) {
            failures.add(new CaseFailure(measureId, caseId, detail));
            return this;
        }

        public Suite run(String key, Object value) {
            run.put(key, value);
            return this;
        }

        public String name() {
            return name;
        }

        public List<Measure> measures() {
            return measures;
        }

        public List<CaseFailure> failures() {
            return failures;
        }

        /** Gated measures whose verdict is FAIL: what the suite's own test asserts is empty. */
        public List<String> blockingFailures() {
            List<String> out = new ArrayList<>();
            for (Measure m : measures) if (m.gated() && m.verdict() == Verdict.FAIL) out.add(m.id() + " observed=" + m.observed() + " threshold=" + m.threshold() + " n=" + m.n());
            return out;
        }

        public ObjectNode toJson() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("suite", name);
            root.set("run", MAPPER.valueToTree(run));
            var arr = root.putArray("measures");
            for (Measure m : measures) {
                ObjectNode o = arr.addObject();
                o.put("id", m.id());
                o.put("dimension", m.dimension());
                o.put("description", m.description());
                o.put("kind", m.kind().name());
                o.put("observed", round(m.observed()));
                o.put("threshold", m.threshold());
                o.put("n", m.n());
                o.put("minN", m.minN());
                o.put("gated", m.gated());
                o.put("verdict", m.verdict().name());
                if (m.note() != null) o.put("note", m.note());
            }
            var ch = root.putArray("characterisations");
            for (Characterisation c : characterisations) {
                ObjectNode o = ch.addObject();
                o.put("id", c.id());
                o.put("description", c.description());
                o.set("values", MAPPER.valueToTree(c.values()));
            }
            var fl = root.putArray("failures");
            for (CaseFailure f : failures) {
                ObjectNode o = fl.addObject();
                o.put("measure", f.measureId());
                o.put("case", f.caseId());
                o.put("detail", f.detail());
            }
            return root;
        }

        /** Writes target/p6-eval/results/&lt;suite&gt;.json (stable key order, no timestamps) and fails if a gated measure FAILED. */
        public void writeAndGate() throws IOException {
            Path dir = Path.of("target", "p6-eval", "results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".json"), MAPPER.writeValueAsString(toJson()) + "\n", StandardCharsets.UTF_8);
            List<String> blocking = blockingFailures();
            if (!blocking.isEmpty()) throw new AssertionError("release-blocking evaluation failures in suite " + name + ": " + blocking + " ; first cases: " + failures.stream().limit(8).toList());
        }
    }

    public static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    // ---------------------------------------------------------------- corpus

    /** Loads a corpus file from the classpath after checking it against the manifest's SHA-256: an edited corpus without a regenerated manifest is refused. */
    public static JsonNode corpus(String file) {
        try {
            byte[] manifestBytes = resource("manifest.json");
            JsonNode manifest = MAPPER.readTree(manifestBytes);
            byte[] bytes = resource(file);
            String expected = manifest.path("files").path(file).path("sha256").asText(null);
            if (expected == null) throw new IllegalStateException("corpus file " + file + " is not listed in the manifest");
            String actual = sha256(normalise(bytes));
            if (!expected.equals(actual)) throw new IllegalStateException("corpus file " + file + " does not match the manifest hash (edited without regenerating the manifest)");
            if (!CORPUS_VERSION.equals(manifest.path("version").asText())) throw new IllegalStateException("corpus version mismatch");
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read corpus " + file, e);
        }
    }

    private static byte[] resource(String file) throws IOException {
        try (InputStream in = Eval.class.getResourceAsStream("/eval/p6/corpus-v1/" + file)) {
            if (in == null) throw new IllegalStateException("missing corpus file " + file);
            return in.readAllBytes();
        }
    }

    /** Line endings are normalised so a CRLF checkout hashes like an LF one. */
    static byte[] normalise(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
