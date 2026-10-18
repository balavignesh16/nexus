"""Phase 5 gate guarantees (ledger P5-126, P5-149, P5-150): the segmented backend check cannot pass with a missing, crashed, timed-out or failing
segment, a duplicate class or an unexpected class, the full gate fails with it, the cost of the check is reported against the Phase 4 baseline, and
the official full gate contains the Phase 5 checks. A fake Maven runner stands in for the real one, so every failure mode is exercised in
milliseconds and without a database."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import backend_batches as bb  # noqa: E402
import nexus_runtime as rt  # noqa: E402
import validate_nexus as vn  # noqa: E402

CLASSES = ("p.ATest", "p.BTest", "p.CTest")


def make_sources(directory: Path) -> Path:
    for name in CLASSES:
        simple = name.split(".")[1]
        path = directory / "p" / f"{simple}.java"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"package p;\nclass {simple} {{ @Test void t() {{}} }}\n", encoding="utf-8")
    return directory


def report(directory: Path, name: str, tests: int = 1, failures: int = 0, errors: int = 0) -> None:
    (directory / f"TEST-{name}.xml").write_text(f'<testsuite name="{name}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="0"/>', encoding="utf-8")


class SegmentedCheck(unittest.TestCase):
    """bb.run with a fake Maven: `script(batch_index, class_names, reports_dir)` writes the reports and returns the exit code (or raises)."""

    def run_with(self, script):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            sources, reports, logs = make_sources(tmp / "src"), tmp / "reports", tmp / "logs"
            reports.mkdir()
            calls = {"n": 0}

            def fake_run(command, **kwargs):
                calls["n"] += 1
                names = [a.removeprefix("-Dtest=") for a in command if a.startswith("-Dtest=")][0].split(",")
                reports.mkdir(parents=True, exist_ok=True)   # run() removes the directory before every segment, like Maven's clean report folder
                code = script(calls["n"], names, reports)
                return subprocess.CompletedProcess(command, code, stdout="", stderr="")

            with mock.patch.object(bb.subprocess, "run", fake_run), mock.patch.object(bb, "free_mb", lambda: 10**6), \
                    mock.patch.object(bb, "REPORTS", reports), mock.patch.object(bb.read_reports, "__defaults__", (reports,)):
                return bb.run("mvnw", 600, classes=bb.discover(sources), max_weight=1, log_dir=logs)

    @staticmethod
    def good(index, names, reports):
        for name in names:
            report(reports, name)
        return 0

    def test_p5_149_a_clean_run_passes_and_reports_its_cost_against_the_phase4_baseline(self):
        result = self.run_with(self.good)
        self.assertTrue(result.ok, result.summary)
        self.assertEqual(3, result.detail["batches"])
        self.assertEqual(3, result.detail["classesWithReports"])
        # P5-150: segments, classes, tests, seconds per segment and in total, lowest free memory and the difference from the baseline
        self.assertEqual(1541, result.detail["baselineSeconds"])
        self.assertEqual(result.detail["seconds"] - 1541, result.detail["differenceFromBaselineSeconds"])
        for needle in ("3/3 batches", "3/3 classes", "tests 3", "min free", "against the 1541s baseline", "seconds per batch ["):
            self.assertIn(needle, result.summary)
        self.assertEqual(3, len(result.detail["rows"]))
        self.assertTrue(all("seconds" in row and "freeMb" in row for row in result.detail["rows"]))

    def test_p5_149_a_segment_without_a_report_fails(self):
        result = self.run_with(lambda i, names, reports: 0)
        self.assertFalse(result.ok)
        self.assertIn("no report for", result.summary)

    def test_p5_149_a_crashed_segment_fails(self):
        result = self.run_with(lambda i, names, reports: 1)
        self.assertFalse(result.ok)
        self.assertIn("Maven exit code 1", result.summary)

    def test_p5_149_a_timed_out_segment_fails(self):
        def timeout(i, names, reports):
            raise subprocess.TimeoutExpired("mvnw", 5)
        result = self.run_with(timeout)
        self.assertFalse(result.ok)
        self.assertIn("Maven exit code -1", result.summary)

    def test_p5_149_a_failing_test_fails_even_when_maven_exits_zero(self):
        def failing(i, names, reports):
            for name in names:
                report(reports, name, tests=2, failures=1 if i == 2 else 0)
            return 0
        result = self.run_with(failing)
        self.assertFalse(result.ok)
        self.assertIn("failing: p.BTest", result.summary)

    def test_p5_149_a_duplicate_class_fails(self):
        def duplicate(i, names, reports):
            for name in names:
                report(reports, name)
            if i == 2:
                report(reports, "p.ATest")   # the class of the first segment runs again in the second
            return 0
        result = self.run_with(duplicate)
        self.assertFalse(result.ok)
        self.assertTrue("ran twice: p.ATest" in result.summary or "unexpected classes ran: p.ATest" in result.summary, result.summary)

    def test_p5_149_an_unexpected_class_fails(self):
        def unexpected(i, names, reports):
            for name in names:
                report(reports, name)
            report(reports, "p.GhostTest")
            return 0
        result = self.run_with(unexpected)
        self.assertFalse(result.ok)
        self.assertIn("unexpected classes ran: p.GhostTest", result.summary)

    def test_p5_149_a_segment_that_never_runs_because_the_budget_is_gone_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            sources = make_sources(tmp / "src")
            with mock.patch.object(bb, "free_mb", lambda: 10**6):
                result = bb.run("mvnw", 0, classes=bb.discover(sources), max_weight=1, log_dir=tmp / "logs")
        self.assertFalse(result.ok)
        self.assertIn("budget of 0s exhausted", result.summary)


class FullGate(unittest.TestCase):
    """validate_nexus.py --full with every check replaced by a canned one: only the gate's own decisions are under test."""

    def run_gate(self, backend_status="PASS", argv=("--full",)):
        names = []

        def fake_execute(name, command, cwd, timeout, verbose):
            names.append(name)
            return vn.Check(name, "PASS", rt.utc_now(), 1, "completed successfully")

        def fake_backend(mvnw, verbose):
            names.append("complete backend and integration suite")
            return vn.Check("complete backend and integration suite", backend_status, rt.utc_now(), 1, "canned")

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(vn, "execute", fake_execute), mock.patch.object(vn, "backend_batches_check", fake_backend), \
                mock.patch.object(rt, "live_orchestrator", lambda: False), mock.patch.object(rt, "port_open", lambda port: False), \
                mock.patch.object(rt, "VALIDATION_DIR", Path(tmp)), mock.patch.object(sys, "argv", ["validate_nexus.py", *argv]):
            code = vn.main()
            latest = json.loads((Path(tmp) / "latest.json").read_text(encoding="utf-8"))
        return code, latest, names

    def test_p5_126_the_full_gate_contains_the_phase5_checks(self):
        code, latest, names = self.run_gate()
        self.assertEqual(0, code)
        self.assertEqual("PASS", latest["overall"])
        for expected in ("wire contract validator and device SDK tests (nexus_contract, nexus_device_sdk)",
                         "phase 5 adapter conformance kit and extensibility proof (unit level)",
                         "phase 5 adapter smoke (MQTT, HTTP and simulator conformance, mixed fleet, on PostgreSQL and Mosquitto)",
                         "complete backend and integration suite"):
            self.assertIn(expected, names)
        self.assertEqual(len(names), len(set(names)), "no check runs twice")

    def test_p5_149_the_full_gate_fails_when_the_backend_check_fails(self):
        code, latest, names = self.run_gate(backend_status="FAIL")
        self.assertEqual(1, code)
        self.assertEqual("FAIL", latest["overall"])
        self.assertEqual(["complete backend and integration suite"], latest["newFailures"])

    def test_p5_149_an_orchestration_fault_is_a_failed_check_never_a_silent_pass(self):
        with mock.patch.object(vn.backend_batches, "run", side_effect=RuntimeError("inventory fault")):
            check = vn.backend_batches_check("mvnw", False)
        self.assertEqual("FAIL", check.status)
        self.assertIn("inventory fault", check.reason)
        failed = bb.Result(False, "FAIL: one segment without a report", {})
        with mock.patch.object(vn.backend_batches, "run", return_value=failed):
            self.assertEqual("FAIL", vn.backend_batches_check("mvnw", False).status)
        ok = bb.Result(True, "PASS", {})
        with mock.patch.object(vn.backend_batches, "run", return_value=ok):
            self.assertEqual("PASS", vn.backend_batches_check("mvnw", False).status)

    def test_the_fast_gate_does_not_run_the_backend_batches(self):
        code, latest, names = self.run_gate(argv=("--fast",))
        self.assertEqual(0, code)
        self.assertNotIn("complete backend and integration suite", names)
        self.assertIn("backend compile", names)


if __name__ == "__main__":
    unittest.main()
