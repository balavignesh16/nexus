"""Ledger P6-072, P6-085, P6-086, P6-087: the evaluation release gate decides correctly.

The gate (tests/p6_eval_gate.py) is what turns measured scores into a release decision, so its logic is tested on synthetic result sets before anything it
reports is trusted: PASS needs every gated measure to pass on enough cases, a FAIL blocks, a missing or empty evaluation can never become a pass, a doctored
result file is caught by recomputing its verdict, a changed score is a regression, and a corpus that differs from its manifest blocks.
"""
from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import p6_eval_gate as gate  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def measure(mid, kind="ZERO", observed=0, threshold=0, n=10, min_n=1, gated=True, verdict=None):
    m = {"id": mid, "dimension": "d", "description": "x", "kind": kind, "observed": observed, "threshold": threshold, "n": n, "minN": min_n, "gated": gated}
    m["verdict"] = verdict or gate.verdict_of(m)
    return m


def suite_result(*measures, version="p6-eval-corpus-v1"):
    return {"suite": "s", "run": {"corpusVersion": version, "provider": "scripted-offline"}, "measures": list(measures), "characterisations": [], "failures": []}


def complete(**overrides):
    results = {s: suite_result(measure(f"{s}.ok")) for s in gate.REQUIRED_SUITES}
    results.update(overrides)
    return results


class VerdictLogic(unittest.TestCase):
    def test_zero_passes_only_at_zero_and_needs_cases(self):
        self.assertEqual("PASS", gate.verdict_of(measure("a", "ZERO", 0, n=5)))
        self.assertEqual("FAIL", gate.verdict_of(measure("a", "ZERO", 1, n=5)))
        self.assertEqual("NOT_EVALUATED", gate.verdict_of(measure("a", "ZERO", 0, n=0)), "no cases is not a pass")
        self.assertEqual("INCONCLUSIVE", gate.verdict_of(measure("a", "ZERO", 0, n=2, min_n=5)), "too few cases is not a pass")

    def test_at_least_and_at_most_compare_with_the_threshold_inclusively(self):
        self.assertEqual("PASS", gate.verdict_of(measure("a", "AT_LEAST", 0.9, 0.9)))
        self.assertEqual("FAIL", gate.verdict_of(measure("a", "AT_LEAST", 0.89, 0.9)))
        self.assertEqual("PASS", gate.verdict_of(measure("a", "AT_MOST", 0.05, 0.05)))
        self.assertEqual("FAIL", gate.verdict_of(measure("a", "AT_MOST", 0.0501, 0.05)))

    def test_an_unknown_kind_is_an_error_not_a_pass(self):
        with self.assertRaises(ValueError):
            gate.verdict_of(measure("a", "SOMETHING", 0, 0))


class OverallDecision(unittest.TestCase):
    def test_every_suite_passing_is_pass(self):
        self.assertEqual("PASS", gate.evaluate(complete(), None, [])["overall"])

    def test_a_failed_gated_measure_blocks(self):
        r = gate.evaluate(complete(drafts=suite_result(measure("drafts.bad", observed=1))), None, [])
        self.assertEqual("BLOCKED", r["overall"])
        self.assertEqual(["drafts.bad"], r["blocking"])

    def test_an_ungated_failure_is_reported_but_does_not_block(self):
        r = gate.evaluate(complete(anomaly=suite_result(measure("anomaly.heavy", "AT_MOST", 0.9, 0.05, gated=False))), None, [])
        self.assertEqual("PASS", r["overall"])
        self.assertEqual(["anomaly.heavy"], r["informationalFailures"])

    def test_a_missing_suite_is_never_a_pass(self):
        results = complete()
        results["injection"] = None
        r = gate.evaluate(results, None, [])
        self.assertEqual("INCOMPLETE", r["overall"])
        self.assertEqual(["injection"], r["missingSuites"])

    def test_an_unevaluated_or_inconclusive_gated_measure_is_incomplete_not_pass(self):
        for m in (measure("x.none", n=0), measure("x.few", n=3, min_n=10)):
            r = gate.evaluate(complete(narrator=suite_result(m)), None, [])
            self.assertEqual("INCOMPLETE", r["overall"], m["id"])
            self.assertIn(m["id"], r["incomplete"])

    def test_a_doctored_result_file_is_caught_by_recomputing_the_verdict(self):
        lie = measure("lie", observed=3)
        lie["verdict"] = "PASS"
        r = gate.evaluate(complete(prose=suite_result(lie)), None, [])
        self.assertEqual("BLOCKED", r["overall"])
        self.assertTrue(any("lie" in p for p in r["problems"]))
        self.assertIn("lie", r["blocking"], "the recomputed verdict is FAIL")

    def test_suites_on_different_corpus_versions_block(self):
        r = gate.evaluate(complete(drafts=suite_result(measure("d.ok"), version="p6-eval-corpus-v0")), None, [])
        self.assertEqual("BLOCKED", r["overall"])

    def test_a_corpus_problem_blocks(self):
        self.assertEqual("BLOCKED", gate.evaluate(complete(), None, ["corpus file x differs from the manifest hash"])["overall"])


class OptionalRealModel(unittest.TestCase):
    def test_an_absent_real_model_run_is_not_evaluated_and_never_a_pass_or_a_failure(self):
        r = gate.evaluate(complete(), None, [])
        self.assertEqual(["realmodel"], r["optionalNotEvaluated"])
        self.assertEqual("PASS", r["overall"], "an optional evaluation that was not configured does not block")
        self.assertIn("No real model was called", gate.markdown(r))

    @staticmethod
    def real_run(*measures, sha=None, drop=None):
        frozen = json.loads(gate.HELDOUT_MANIFEST.read_text(encoding="utf-8"))["sha256"]
        run = {"corpusVersion": "p6-eval-corpus-v1", "provider": "ollama-local", "model": "m", "modelDigest": "d", "configurationHash": "c", "contractHash": "h", "evaluatedAt": "2026-10-20T00:00:00Z",
               "heldOutCorpusSha256": sha or frozen}
        if drop:
            run.pop(drop)
        return {"suite": "realmodel", "run": run, "measures": list(measures), "characterisations": [], "failures": []}

    def test_a_present_real_model_run_is_judged_like_any_other_suite(self):
        bad = self.real_run(measure("realmodel.bad", observed=2))
        r = gate.evaluate(complete(realmodel=bad), None, [])
        self.assertEqual("BLOCKED", r["overall"])
        self.assertEqual([], r["optionalNotEvaluated"])

    def test_a_real_model_run_with_passing_gated_and_failing_informational_measures_does_not_block_and_discloses_the_failures(self):
        run = self.real_run(measure("realmodel.safe"), measure("realmodel.accuracy", kind="AT_LEAST", observed=0.1, threshold=0.7, gated=False))
        r = gate.evaluate(complete(realmodel=run), None, [])
        self.assertEqual("PASS", r["overall"])
        self.assertIn("realmodel.accuracy", r["informationalFailures"])

    def test_a_real_model_run_on_another_corpus_or_without_its_identity_blocks(self):
        self.assertEqual("BLOCKED", gate.evaluate(complete(realmodel=self.real_run(measure("realmodel.safe"), sha="0" * 64)), None, [])["overall"])
        for missing in ("model", "modelDigest", "configurationHash", "contractHash", "evaluatedAt"):
            r = gate.evaluate(complete(realmodel=self.real_run(measure("realmodel.safe"), drop=missing)), None, [])
            self.assertEqual("BLOCKED", r["overall"], missing)
            self.assertTrue(any(missing in p for p in r["problems"]), missing)

    def test_the_real_model_suite_is_never_compared_with_the_deterministic_baseline(self):
        baseline = gate.baseline_from(gate.evaluate(complete(), None, []))
        r = gate.evaluate(complete(realmodel=self.real_run(measure("realmodel.safe"))), baseline, [])
        self.assertEqual([], r["regressions"])
        self.assertEqual("PASS", r["overall"])


class BaselineComparison(unittest.TestCase):
    def baseline_of(self, results):
        return gate.baseline_from(gate.evaluate(results, None, []))

    def test_identical_scores_are_not_a_regression(self):
        results = complete()
        self.assertEqual("PASS", gate.evaluate(results, self.baseline_of(results), [])["overall"])

    def test_a_changed_score_is_a_regression_even_if_it_still_passes(self):
        results = complete(narrator=suite_result(measure("n.rate", "AT_LEAST", 1.0, 0.9)))
        base = self.baseline_of(results)
        changed = complete(narrator=suite_result(measure("n.rate", "AT_LEAST", 0.95, 0.9)))
        r = gate.evaluate(changed, base, [])
        self.assertEqual("INCOMPLETE", r["overall"])
        self.assertTrue(any("n.rate" in x for x in r["regressions"]))

    def test_a_measure_that_disappears_or_appears_is_a_regression(self):
        base = self.baseline_of(complete())
        fewer = complete()
        fewer["narrator"] = suite_result()
        self.assertTrue(gate.evaluate(fewer, base, [])["regressions"])
        more = complete(narrator=suite_result(measure("narrator.ok"), measure("narrator.new")))
        self.assertTrue(any("narrator.new" in x for x in gate.evaluate(more, base, [])["regressions"]))

    def test_timing_figures_are_not_compared_exactly(self):
        a = complete(performance=suite_result(measure("perf.size", "AT_MOST", 100, 1000, n=1)))
        b = complete(performance=suite_result(measure("perf.size", "AT_MOST", 900, 1000, n=1)))
        self.assertEqual("PASS", gate.evaluate(b, self.baseline_of(a), [])["overall"])

    def test_the_baseline_is_not_recorded_from_a_blocked_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "results").mkdir()
            for s in gate.REQUIRED_SUITES:
                (tmp / "results" / f"{s}.json").write_text(json.dumps(suite_result(measure(f"{s}.bad", observed=1))), encoding="utf-8")
            code = gate.main(["--results", str(tmp / "results"), "--baseline", str(tmp / "b.json"), "--corpus", str(gate.CORPUS), "--write-baseline"])
            self.assertEqual(1, code)
            self.assertFalse((tmp / "b.json").exists())


class CorpusIntegrity(unittest.TestCase):
    def test_the_committed_corpus_matches_its_manifest(self):
        self.assertEqual([], gate.corpus_problems(gate.CORPUS))
        done = subprocess.run([sys.executable, str(ROOT / "tests/p6_eval_manifest.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(0, done.returncode, done.stderr)

    def test_an_edited_corpus_file_an_unlisted_file_and_a_missing_file_are_each_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            for f in gate.CORPUS.glob("*.json"):
                (tmp / f.name).write_bytes(f.read_bytes())
            self.assertEqual([], gate.corpus_problems(tmp))
            edited = tmp / "narrator.json"
            edited.write_text(edited.read_text(encoding="utf-8").replace("N-OK-01", "N-OK-01x", 1), encoding="utf-8")
            self.assertTrue(any("narrator.json differs" in p for p in gate.corpus_problems(tmp)))
            (tmp / "extra.json").write_text("{}", encoding="utf-8")
            self.assertTrue(any("extra.json is not in the manifest" in p for p in gate.corpus_problems(tmp)))
            (tmp / "prose.json").unlink()
            self.assertTrue(any("prose.json" in p and "missing" in p for p in gate.corpus_problems(tmp)))

    def test_the_corpus_is_the_size_the_report_claims_and_covers_every_required_input_class(self):
        manifest = json.loads((gate.CORPUS / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual({"anomaly.json", "drafts.json", "injection.json", "lifecycle.json", "narrator.json", "nonmatch.json", "prose.json"}, set(manifest["files"]))
        self.assertGreaterEqual(manifest["totalCases"], 250)
        self.assertTrue(all(v["provenance"] != "unspecified" for v in manifest["files"].values()))


class Reporting(unittest.TestCase):
    def test_the_report_states_it_measures_a_finite_corpus(self):
        text = gate.markdown(gate.evaluate(complete(), None, []))
        self.assertIn("not a guarantee", text)
        self.assertIn("PASS", text)

    def test_the_exit_code_is_zero_only_for_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "results").mkdir()
            for s in gate.REQUIRED_SUITES:
                (tmp / "results" / f"{s}.json").write_text(json.dumps(suite_result(measure(f"{s}.ok"))), encoding="utf-8")
            args = ["--results", str(tmp / "results"), "--baseline", str(tmp / "none.json"), "--corpus", str(gate.CORPUS)]
            self.assertEqual(0, gate.main(args))
            (tmp / "results" / "drafts.json").unlink()
            self.assertEqual(1, gate.main(args))


if __name__ == "__main__":
    unittest.main()
