"""The Phase 6 evaluation release gate (ledger P6-072, P6-085, P6-086, P6-087).

    python tests/p6_eval_gate.py                      # read backend/target/p6-eval/results, print the verdict, exit 0 only on PASS
    python tests/p6_eval_gate.py --write-baseline     # record the current deterministic results as the committed baseline (a deliberate act)
    python tests/p6_eval_gate.py --markdown out.md    # also write the human report

Every JUnit suite measures one finite corpus and writes <suite>.json. This gate does not trust those files: it recomputes each measure's verdict from its
own fields, requires every suite, checks that the corpus on disk matches the versioned manifest, and compares the deterministic measures with a committed
baseline so a change in any score is visible.

Verdicts: PASS = the measured criterion is met. FAIL = it is violated. NOT_EVALUATED = the evaluation did not run or has no cases. INCONCLUSIVE = it ran on
too few cases to support a verdict. Overall: PASS only if every required suite ran, every gated measure is PASS and nothing regressed against the baseline;
BLOCKED if a gated measure FAILED; INCOMPLETE if something required was not evaluated or is inconclusive. A passing finite suite is a measurement of that
corpus, never a guarantee against hallucination or prompt injection in general.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "backend/target/p6-eval/results"
BASELINE = ROOT / "architecture/phase6/evaluation/results-baseline-v1.json"
CORPUS = ROOT / "backend/src/test/resources/eval/p6/corpus-v1"

REQUIRED_SUITES = ["narrator", "prose", "injection", "anomaly", "drafts", "lifecycle", "nonmatch", "isolation", "performance"]
# Run only when someone explicitly configures a real model; reported as NOT_EVALUATED (never as a pass) when absent, and included when present.
OPTIONAL_SUITES = ["realmodel"]
# Timing-dependent suites and measures are reported but not compared exactly with the baseline: their figures vary by machine and run.
NOT_COMPARED_SUITES = {"performance"}
NOT_COMPARED_MEASURES = {"isolation.ingestion_max_latency_ms"}
VERDICTS = ("PASS", "FAIL", "NOT_EVALUATED", "INCONCLUSIVE")


def verdict_of(m: dict) -> str:
    """The verdict a measure's own fields imply (kind ZERO, AT_LEAST or AT_MOST; n and minN decide NOT_EVALUATED and INCONCLUSIVE)."""
    n, min_n = m["n"], m["minN"]
    if n <= 0:
        return "NOT_EVALUATED"
    if n < min_n:
        return "INCONCLUSIVE"
    kind, observed, threshold = m["kind"], m["observed"], m["threshold"]
    if kind == "ZERO":
        ok = observed == 0
    elif kind == "AT_LEAST":
        ok = observed >= threshold
    elif kind == "AT_MOST":
        ok = observed <= threshold
    else:
        raise ValueError(f"unknown measure kind {kind}")
    return "PASS" if ok else "FAIL"


def load_results(results_dir: Path) -> dict[str, dict]:
    out = {}
    for suite in REQUIRED_SUITES + OPTIONAL_SUITES:
        path = results_dir / f"{suite}.json"
        out[suite] = json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
    return out


def corpus_problems(corpus_dir: Path) -> list[str]:
    import hashlib

    manifest_path = corpus_dir / "manifest.json"
    if not manifest_path.exists():
        return ["the corpus manifest is missing"]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    problems = []
    for name, entry in manifest.get("files", {}).items():
        path = corpus_dir / name
        if not path.exists():
            problems.append(f"corpus file {name} listed in the manifest is missing")
        elif hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest() != entry["sha256"]:
            problems.append(f"corpus file {name} differs from the manifest hash")
    listed = set(manifest.get("files", {}))
    for path in corpus_dir.glob("*.json"):
        if path.name != "manifest.json" and path.name not in listed:
            problems.append(f"corpus file {path.name} is not in the manifest")
    return problems


def evaluate(results: dict[str, dict | None], baseline: dict | None, corpus_issues: list[str]) -> dict:
    measures, problems, missing = [], list(corpus_issues), []
    versions, providers = set(), set()
    optional_not_evaluated = []
    for suite in REQUIRED_SUITES + OPTIONAL_SUITES:
        data = results.get(suite)
        if data is None:
            (missing if suite in REQUIRED_SUITES else optional_not_evaluated).append(suite)
            continue
        versions.add(data.get("run", {}).get("corpusVersion"))
        providers.add(data.get("run", {}).get("provider"))
        for m in data["measures"]:
            recomputed = verdict_of(m)
            if recomputed != m["verdict"]:
                problems.append(f"{m['id']}: the file says {m['verdict']} but its own figures give {recomputed}")
            measures.append({**m, "suite": suite, "verdict": recomputed})
    if len(versions - {None}) > 1:
        problems.append(f"suites ran on different corpus versions: {sorted(v for v in versions if v)}")

    blocking = [m for m in measures if m["gated"] and m["verdict"] == "FAIL"]
    incomplete = [m for m in measures if m["gated"] and m["verdict"] in ("NOT_EVALUATED", "INCONCLUSIVE")]
    informational_fail = [m for m in measures if not m["gated"] and m["verdict"] == "FAIL"]

    regressions = []
    if baseline is not None:
        current = {m["id"]: m for m in measures if m["suite"] not in NOT_COMPARED_SUITES and m["id"] not in NOT_COMPARED_MEASURES}
        for mid, b in baseline["measures"].items():
            c = current.get(mid)
            if c is None:
                if b["suite"] not in missing:
                    regressions.append(f"{mid}: present in the baseline, absent now")
                continue
            if c["verdict"] != b["verdict"] or abs(c["observed"] - b["observed"]) > 1e-9 or c["n"] != b["n"]:
                regressions.append(f"{mid}: baseline {b['verdict']} {b['observed']} (n={b['n']}), now {c['verdict']} {c['observed']} (n={c['n']})")
        for mid in current:
            if mid not in baseline["measures"]:
                regressions.append(f"{mid}: not in the baseline (record it deliberately with --write-baseline)")

    if blocking or problems:
        overall = "BLOCKED"
    elif missing or incomplete or regressions:
        overall = "INCOMPLETE"
    else:
        overall = "PASS"
    return {"overall": overall, "optionalNotEvaluated": optional_not_evaluated, "missingSuites": missing, "blocking": [m["id"] for m in blocking], "incomplete": [m["id"] for m in incomplete],
            "informationalFailures": [m["id"] for m in informational_fail], "regressions": regressions, "problems": problems, "providers": sorted(p for p in providers if p),
            "corpusVersions": sorted(v for v in versions if v), "measures": measures}


def baseline_from(report: dict) -> dict:
    keep = {m["id"]: {"suite": m["suite"], "verdict": m["verdict"], "observed": m["observed"], "n": m["n"]} for m in report["measures"]
            if m["suite"] not in NOT_COMPARED_SUITES and m["id"] not in NOT_COMPARED_MEASURES}
    return {"description": "Deterministic evaluation results recorded from a run of corpus p6-eval-corpus-v1. Compared exactly by tests/p6_eval_gate.py; a change in any score is a regression until recorded again deliberately.", "measures": dict(sorted(keep.items()))}


def markdown(report: dict) -> str:
    lines = [f"# Phase 6 evaluation: {report['overall']}", "", f"Corpus {', '.join(report['corpusVersions'])}; provider {', '.join(report['providers'])} (no real model was called unless stated).", ""]
    if report["missingSuites"]:
        lines += [f"NOT EVALUATED suites: {', '.join(report['missingSuites'])}", ""]
    if report["optionalNotEvaluated"]:
        lines += [f"NOT_EVALUATED (optional, explicitly configured): {', '.join(report['optionalNotEvaluated'])}. No real model was called.", ""]
    for key, title in (("blocking", "Gated measures that FAILED"), ("incomplete", "Gated measures NOT_EVALUATED or INCONCLUSIVE"), ("informationalFailures", "Ungated measures outside their proposed bound"), ("regressions", "Changes against the baseline"), ("problems", "Problems")):
        if report[key]:
            lines += [f"## {title}", ""] + [f"* {x}" for x in report[key]] + [""]
    lines += ["| suite | measure | kind | observed | threshold | n | gated | verdict |", "|---|---|---|---|---|---|---|---|"]
    for m in report["measures"]:
        lines.append(f"| {m['suite']} | {m['id']} | {m['kind']} | {m['observed']} | {m['threshold']} | {m['n']} | {'yes' if m['gated'] else 'no'} | {m['verdict']} |")
    lines += ["", "These are measurements of a finite corpus. They are not a guarantee against hallucination or prompt injection in general."]
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, default=RESULTS)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--corpus", type=Path, default=CORPUS)
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args(argv)

    baseline = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.exists() and not args.write_baseline else None
    report = evaluate(load_results(args.results), baseline, corpus_problems(args.corpus))
    if args.write_baseline:
        if report["overall"] == "BLOCKED" or report["missingSuites"]:
            print("refusing to record a baseline from a blocked or incomplete run", file=sys.stderr)
            return 1
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(json.dumps(baseline_from(report), indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
        print(f"baseline recorded: {len(baseline_from(report)['measures'])} measures")
        return 0
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(markdown(report), encoding="utf-8", newline="\n")
    counts = {v: sum(1 for m in report["measures"] if m["verdict"] == v) for v in VERDICTS}
    print(f"P6 evaluation gate: {report['overall']}  measures={len(report['measures'])} {counts}  missing={report['missingSuites']}  optionalNotEvaluated={report['optionalNotEvaluated']}")
    for key in ("blocking", "incomplete", "informationalFailures", "regressions", "problems"):
        for item in report[key]:
            print(f"  {key}: {item}")
    return 0 if report["overall"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
