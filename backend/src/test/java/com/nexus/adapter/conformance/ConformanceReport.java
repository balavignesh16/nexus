package com.nexus.adapter.conformance;

import com.nexus.adapter.spi.AdapterCapability;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The outcome of running the conformance kit against ONE adapter. Four outcomes, never blurred:
 * <ul>
 *   <li>PASS: an implemented capability satisfied the scenario.</li>
 *   <li>FAIL: an implemented capability violated the scenario.</li>
 *   <li>NOT_SUPPORTED: the adapter does not declare the capability; nothing was exercised and nothing is claimed.</li>
 *   <li>NOT_APPLICABLE: the requirement does not apply to this transport (with the reason), or the subject cannot exercise it at this level.</li>
 * </ul>
 * NOT_SUPPORTED and NOT_APPLICABLE are never counted as passes, and a declared capability without a single PASS is not conformant.
 */
public final class ConformanceReport {
    public enum Outcome { PASS, FAIL, NOT_SUPPORTED, NOT_APPLICABLE }

    public record Result(String scenario, String capability, Outcome outcome, String detail) {
        public Result {
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(outcome, "outcome");
            detail = detail == null ? "" : detail;
        }
    }

    private final String subject;
    private final String level;
    private final Set<AdapterCapability> declared;
    private final List<Result> results;

    public ConformanceReport(String subject, String level, Set<AdapterCapability> declared, List<Result> results) {
        this.subject = subject;
        this.level = level;
        this.declared = Set.copyOf(declared);
        this.results = List.copyOf(results);
    }

    public String subject() { return subject; }
    public String level() { return level; }
    public Set<AdapterCapability> declared() { return declared; }
    public List<Result> results() { return results; }

    public List<Result> of(Outcome outcome) {
        return results.stream().filter(r -> r.outcome == outcome).collect(Collectors.toList());
    }

    public List<Result> forCapability(String capability) {
        return results.stream().filter(r -> r.capability.equals(capability)).collect(Collectors.toList());
    }

    public Result scenario(String id) {
        return results.stream().filter(r -> r.scenario.equals(id)).findFirst().orElseThrow(() -> new AssertionError("no scenario " + id + " in the report of " + subject));
    }

    /** PASS, FAIL, NOT_SUPPORTED, NOT_APPLICABLE counts. */
    public Map<Outcome, Long> counts() {
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) counts.put(outcome, results.stream().filter(r -> r.outcome == outcome).count());
        return counts;
    }

    /** Capabilities that were declared and have at least one PASS and no FAIL. */
    public Set<String> conformantCapabilities() {
        return declared.stream().map(Enum::name).filter(c -> hasPass(c) && !hasFail(c)).collect(Collectors.toSet());
    }

    private boolean hasPass(String capability) {
        return results.stream().anyMatch(r -> r.capability.equals(capability) && r.outcome == Outcome.PASS);
    }

    private boolean hasFail(String capability) {
        return results.stream().anyMatch(r -> r.capability.equals(capability) && r.outcome == Outcome.FAIL);
    }

    /** True only when nothing FAILED and every DECLARED capability earned at least one PASS. Undeclared capabilities do not count either way. */
    public boolean conforms() {
        if (!of(Outcome.FAIL).isEmpty()) return false;
        for (AdapterCapability capability : declared) {
            if (!hasPass(capability.name())) return false;
        }
        return true;
    }

    /** What an operator reads: one line per scenario, the matrix first. Contains no payload text and no identifier. */
    public String text() {
        StringBuilder out = new StringBuilder();
        out.append("Conformance report: ").append(subject).append(" [").append(level).append("]\n");
        for (AdapterCapability capability : AdapterCapability.values()) {
            String state;
            if (!declared.contains(capability)) state = "NOT_SUPPORTED";
            else state = !hasFail(capability.name()) && hasPass(capability.name()) ? "PASS" : hasFail(capability.name()) ? "FAIL" : "NO EVIDENCE";
            out.append(String.format("  %-24s %s%n", capability, state));
        }
        out.append("  totals ").append(counts()).append('\n');
        for (Result r : results) {
            out.append(String.format("    %-13s %-8s %-48s %s%n", r.outcome, r.capability.length() > 8 ? r.capability.substring(0, 8) : r.capability, r.scenario, r.detail));
        }
        return out.toString();
    }

    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        for (Result r : of(Outcome.FAIL)) failures.add(r.scenario + ": " + r.detail);
        return failures;
    }
}
