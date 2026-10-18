"""Tests of backend_batches.py: the memory-safe batching of the complete backend suite must never omit or duplicate a test class, must be
deterministic, must keep the crash-recovery campaign out of the ordinary run (it has its own checks) and must fail loudly on missing reports."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import backend_batches as bb  # noqa: E402


def write(directory: Path, name: str, body: str) -> None:
    path = directory / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


class RealInventory(unittest.TestCase):
    def setUp(self):
        self.classes = bb.discover()

    def test_the_inventory_is_large_unique_and_sorted(self):
        names = [c.fqn for c in self.classes]
        self.assertGreater(len(names), 150)
        self.assertEqual(names, sorted(names))
        self.assertEqual(len(names), len(set(names)))

    def test_every_class_matches_the_surefire_name_patterns_and_is_not_abstract_helper(self):
        for c in self.classes:
            self.assertRegex(c.path.stem, bb.INCLUDE.pattern)
        simple = {c.path.stem for c in self.classes}
        for helper in ("TypedTriggerSupport", "LifecycleFixture", "CrashTestBase", "OutboxTestKit"):
            self.assertNotIn(helper, simple)

    def test_the_crash_campaign_is_excluded_here_because_it_has_its_own_checks(self):
        simple = {c.path.stem for c in self.classes}
        for crash in ("CommandCrashRecoveryTest", "CommandMultiInstanceAndFloodTest", "CommandEnablementProcedureTest"):
            self.assertNotIn(crash, simple)
        pom = (ROOT / "backend" / "pom.xml").read_text(encoding="utf-8")
        self.assertIn("<surefire.excludedGroups>crash,crash-smoke</surefire.excludedGroups>", pom)

    def test_every_test_file_with_a_surefire_name_is_either_listed_or_deliberately_excluded(self):
        listed = {c.path for c in self.classes}
        for path in bb.TEST_SOURCES.rglob("*.java"):
            if not bb.INCLUDE.match(path.stem) or path in listed:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            excluded = ("abstract class " + path.stem in text.replace("public abstract", "abstract").replace("abstract  ", "abstract ")
                        or any(f'@Tag("{tag}")' in text for tag in bb.EXCLUDED_TAGS))
            self.assertTrue(excluded, f"{path.name} matches the Surefire patterns but is neither listed nor excluded on purpose")

    def test_the_partition_covers_every_class_exactly_once_and_respects_the_weight_bound(self):
        batches = bb.partition(self.classes)
        bb.verify_partition(self.classes, batches)
        self.assertGreater(len(batches), 5)
        for batch in batches:
            self.assertTrue(len(batch) == 1 or sum(c.weight for c in batch) <= bb.MAX_BATCH_WEIGHT)

    def test_the_partition_is_deterministic(self):
        first = [[c.fqn for c in b] for b in bb.partition(self.classes)]
        second = [[c.fqn for c in b] for b in bb.partition(bb.discover())]
        self.assertEqual(first, second)


class Synthetic(unittest.TestCase):
    def test_discovery_rules(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            write(src, "a/AlphaTest.java", "package a;\nclass AlphaTest { @Test void t() {} }\n")
            write(src, "a/BetaTests.java", "package a;\nclass BetaTests { @Test void t() {} }\n")
            write(src, "a/TestGamma.java", "package a;\nclass TestGamma { @Test void t() {} }\n")
            write(src, "a/DeltaTestCase.java", "package a;\nclass DeltaTestCase { @Test void t() {} }\n")
            write(src, "a/Helper.java", "package a;\nclass Helper { }\n")
            write(src, "a/BaseTest.java", "package a;\nabstract class BaseTest { @Test void t() {} }\n")
            write(src, "a/Slow.java", "package a;\nclass Slow {}\n")
            write(src, "b/CrashyTest.java", 'package b;\n@Tag("crash")\nclass CrashyTest { @Test void t() {} }\n')
            write(src, "b/IntegrationTest.java", "package b;\n@SpringBootTest\nclass IntegrationTest { @Test void t() {} }\n")
            found = bb.discover(src)
            self.assertEqual(["a.AlphaTest", "a.BetaTests", "a.DeltaTestCase", "a.TestGamma", "b.IntegrationTest"], [c.fqn for c in found])
            self.assertEqual(bb.INTEGRATION_WEIGHT, [c for c in found if c.fqn == "b.IntegrationTest"][0].weight)
            self.assertEqual(1, [c for c in found if c.fqn == "a.AlphaTest"][0].weight)

    def test_a_duplicate_simple_name_in_two_packages_is_still_two_distinct_classes(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            write(src, "a/SameTest.java", "package a;\nclass SameTest { @Test void t() {} }\n")
            write(src, "b/SameTest.java", "package b;\nclass SameTest { @Test void t() {} }\n")
            self.assertEqual(["a.SameTest", "b.SameTest"], [c.fqn for c in bb.discover(src)])

    def test_verify_partition_rejects_an_omission_and_a_duplicate(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            for n in "ABC":
                write(src, f"p/{n}Test.java", f"package p;\nclass {n}Test {{ @Test void t() {{}} }}\n")
            classes = bb.discover(src)
            batches = bb.partition(classes, 2)
            bb.verify_partition(classes, batches)
            with self.assertRaises(AssertionError):
                bb.verify_partition(classes, [batches[0]])                       # a class is omitted
            with self.assertRaises(AssertionError):
                bb.verify_partition(classes, batches + [batches[0]])             # a class is duplicated

    def test_report_reading_merges_nested_classes_and_counts(self):
        with tempfile.TemporaryDirectory() as d:
            reports = Path(d)
            (reports / "TEST-p.ATest.xml").write_text('<testsuite name="p.ATest" tests="3" failures="1" errors="0" skipped="1"/>', encoding="utf-8")
            (reports / "TEST-p.ATest$Inner.xml").write_text('<testsuite name="p.ATest$Inner" tests="2" failures="0" errors="1" skipped="0"/>', encoding="utf-8")
            per = bb.read_reports(reports)
            self.assertEqual({"tests": 5, "failures": 1, "errors": 1, "skipped": 1, "reports": 2}, per["p.ATest"])

    def test_a_missing_report_or_a_failing_exit_code_fails_the_run(self):
        with tempfile.TemporaryDirectory() as d:
            src, logs = Path(d) / "src", Path(d) / "logs"
            write(src, "p/ATest.java", "package p;\nclass ATest { @Test void t() {} }\n")
            fake = Path(d) / "mvnw.cmd"
            fake.write_text("@echo off\r\nexit /b 0\r\n", encoding="utf-8")        # succeeds and writes no report
            result = bb.run(str(fake), 120, classes=bb.discover(src), log_dir=logs)
            self.assertFalse(result.ok)
            self.assertIn("no report for p.ATest", result.summary)


if __name__ == "__main__":
    unittest.main()
