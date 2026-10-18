#!/usr/bin/env python3
"""Memory-safe execution of the complete backend suite (used by validate_nexus.py --full; ledger P4-FINAL).

The former backend check was ONE `mvnw test` over every test class. Each integration class starts its own PostgreSQL and Mosquitto containers
and Spring context, and one forked JVM kept them all, which exhausted memory on the 16 GB development machine. This module runs the SAME set
of test classes, discovered the way Surefire discovers them, as deterministic sequential batches (one Maven process at a time, bounded heap,
bounded Spring context cache), and PROVES the coverage afterwards: every discovered class appears in the Surefire reports exactly once, no
unexpected class ran, and the aggregated counts come from the reports, never from the exit code alone.

It changes no test, no assertion, no timeout and no isolation. Real PostgreSQL, Mosquitto, transactions and concurrency are unchanged because
each class still runs in a real Spring context against real containers; only the number of classes sharing one JVM is bounded.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"
TEST_SOURCES = BACKEND / "src" / "test" / "java"
REPORTS = BACKEND / "target" / "surefire-reports"

# Surefire's default includes: **/Test*.java, **/*Test.java, **/*Tests.java, **/*TestCase.java (nested classes excluded).
INCLUDE = re.compile(r"^(Test.+|.+Test|.+Tests|.+TestCase)$")
TEST_ANNOTATION = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
# Weight of a class for batching: an integration class (containers, Spring context) costs far more than a unit class.
INTEGRATION_MARKERS = ("@SpringBootTest", "@Testcontainers", "TypedTriggerSupport", "LifecycleFixture", "PostgreSQLContainer", "@DataJpaTest", "@WebMvcTest")
MAX_BATCH_WEIGHT = 48            # about 16 integration classes, or many unit classes
INTEGRATION_WEIGHT = 3
JVM_HEAP = "-Xmx1400m"           # bounded fork heap (the former fork used a quarter of the machine's RAM)
MAVEN_HEAP = "-Xmx300m"
CONTEXT_CACHE = "8"              # spring.test.context.cache.maxSize: cached application contexts per JVM
BASELINE_SECONDS = 1541           # measured at P4-FINAL: 219 classes, 1,746 tests, 10 batches; Phase 5 reports its cost against this (decision D10)
MIN_FREE_MB = 1200               # a batch does not start while the machine is below this; the wait counts against the budget
EXCLUDED_TAGS = ("crash", "crash-smoke")   # run by their own checks (pom: surefire.excludedGroups)


@dataclass
class TestClass:
    fqn: str
    path: Path
    weight: int
    expects_tests: bool


@dataclass
class Result:
    ok: bool
    summary: str
    detail: dict = field(default_factory=dict)


def discover(test_sources: Path = TEST_SOURCES) -> list[TestClass]:
    """The classes the ordinary backend run executes: Surefire name patterns, not abstract, not in an excluded group, sorted by name."""
    found: list[TestClass] = []
    for path in sorted(test_sources.rglob("*.java")):
        if not INCLUDE.match(path.stem):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if re.search(r"\babstract\s+class\s+" + re.escape(path.stem) + r"\b", text):
            continue
        if any(re.search(r'@Tag\(\s*"' + re.escape(tag) + r'"\s*\)', text.split("class " + path.stem)[0]) for tag in EXCLUDED_TAGS):
            continue
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        fqn = (package.group(1) + "." if package else "") + path.stem
        integration = any(marker in text for marker in INTEGRATION_MARKERS)
        found.append(TestClass(fqn, path, INTEGRATION_WEIGHT if integration else 1, bool(TEST_ANNOTATION.search(text))))
    fqns = [c.fqn for c in found]
    if len(set(fqns)) != len(fqns):
        raise ValueError("duplicate test class names: " + ", ".join(sorted({f for f in fqns if fqns.count(f) > 1})))
    return sorted(found, key=lambda c: c.fqn)


def partition(classes: list[TestClass], max_weight: int = MAX_BATCH_WEIGHT) -> list[list[TestClass]]:
    """Deterministic sequential batches in name order (classes of one package stay together, which keeps similar Spring contexts together)."""
    batches: list[list[TestClass]] = []
    current: list[TestClass] = []
    weight = 0
    for c in classes:
        if current and weight + c.weight > max_weight:
            batches.append(current)
            current, weight = [], 0
        current.append(c)
        weight += c.weight
    if current:
        batches.append(current)
    return batches


def verify_partition(classes: list[TestClass], batches: list[list[TestClass]]) -> None:
    flat = [c.fqn for batch in batches for c in batch]
    if sorted(flat) != sorted(c.fqn for c in classes) or len(flat) != len(set(flat)):
        raise AssertionError("the batches omit or duplicate a test class")


def free_mb() -> int:
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],
                             capture_output=True, text=True, timeout=30).stdout.strip()
        return int(out) // 1024
    except Exception:
        return 10**6


def read_reports(reports: Path = REPORTS) -> dict[str, dict[str, int]]:
    per: dict[str, dict[str, int]] = {}
    for xml in sorted(reports.glob("TEST-*.xml")):
        root = ET.parse(xml).getroot()
        name = root.get("name", "").split("$")[0]             # a @Nested class reports as Outer$Inner
        entry = per.setdefault(name, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "reports": 0})
        for key in ("tests", "failures", "errors", "skipped"):
            entry[key] += int(root.get(key, 0))
        entry["reports"] += 1
    return per


def run(mvnw: str, deadline_seconds: int, verbose: bool = False, classes: list[TestClass] | None = None,
        max_weight: int = MAX_BATCH_WEIGHT, log_dir: Path | None = None) -> Result:
    started = time.monotonic()
    classes = classes if classes is not None else discover()
    batches = partition(classes, max_weight)
    verify_partition(classes, batches)
    log_dir = log_dir or (ROOT / "validation")
    log_dir.mkdir(parents=True, exist_ok=True)
    executed: dict[str, dict[str, int]] = {}
    batch_rows = []
    min_free = free_mb()
    failures: list[str] = []
    for index, batch in enumerate(batches, 1):
        remaining = deadline_seconds - (time.monotonic() - started)
        if remaining <= 0:
            failures.append(f"budget of {deadline_seconds}s exhausted before batch {index}/{len(batches)}")
            break
        waited = 0
        while free_mb() < MIN_FREE_MB and waited < 120:
            time.sleep(10)
            waited += 10
        min_free = min(min_free, free_mb())
        shutil.rmtree(REPORTS, ignore_errors=True)
        env = dict(os.environ, JAVA_TOOL_OPTIONS=JVM_HEAP, MAVEN_OPTS=MAVEN_HEAP)
        names = ",".join(c.fqn for c in batch)
        batch_started = time.monotonic()
        try:
            proc = subprocess.run([mvnw, f"-Dtest={names}", f"-Dspring.test.context.cache.maxSize={CONTEXT_CACHE}", "test"], cwd=BACKEND, env=env,
                                  capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=max(60, int(remaining)))
            code, output = proc.returncode, (proc.stdout or "") + (proc.stderr or "")
        except subprocess.TimeoutExpired as expired:
            code, output = -1, f"timed out after {int(remaining)}s\n" + str(expired.stdout or "")[-3000:]
        per = read_reports()
        (log_dir / f"backend-batch-{index:02d}.log").write_text(output[-60000:], encoding="utf-8", errors="replace")
        expected = {c.fqn for c in batch}
        missing = sorted(c.fqn for c in batch if c.fqn not in per and c.expects_tests)
        unexpected = sorted(n for n in per if n not in expected)
        bad = {n: v for n, v in per.items() if v["failures"] or v["errors"]}
        duplicate = sorted(n for n in per if n in executed)
        executed.update(per)
        row = {"batch": index, "classes": len(batch), "tests": sum(v["tests"] for v in per.values()), "failures": sum(v["failures"] for v in per.values()),
               "errors": sum(v["errors"] for v in per.values()), "skipped": sum(v["skipped"] for v in per.values()), "exit": code,
               "seconds": round(time.monotonic() - batch_started), "freeMb": free_mb()}
        batch_rows.append(row)
        if verbose:
            print(f"backend batch {index}/{len(batches)}: {row}")
        problems = []
        if code != 0:
            problems.append(f"Maven exit code {code}")
        if missing:
            problems.append("no report for " + ", ".join(missing))
        if unexpected:
            problems.append("unexpected classes ran: " + ", ".join(unexpected))
        if duplicate:
            problems.append("ran twice: " + ", ".join(duplicate))
        if bad:
            problems.append("failing: " + ", ".join(sorted(bad)))
        if problems:
            failures.append(f"batch {index}: " + "; ".join(problems))
            break                                                   # stop at the first bad batch: the later ones would only hide it
    inventory = {c.fqn for c in classes}
    never_ran = sorted(c.fqn for c in classes if c.fqn not in executed and c.expects_tests)
    if not failures and never_ran:
        failures.append("classes with tests but no report: " + ", ".join(never_ran))
    totals = {k: sum(v[k] for v in executed.values()) for k in ("tests", "failures", "errors", "skipped")}
    detail = {"classesDiscovered": len(classes), "classesWithReports": len(executed), "batches": len(batches), "batchesRun": len(batch_rows), "totals": totals,
              "minFreeMbObserved": min_free, "seconds": round(time.monotonic() - started), "rows": batch_rows,
              "classesWithoutTestsOrReports": sorted(inventory - set(executed))}
    detail["baselineSeconds"] = BASELINE_SECONDS
    detail["differenceFromBaselineSeconds"] = detail["seconds"] - BASELINE_SECONDS
    (log_dir / "backend-batches.json").write_text(json.dumps(detail, indent=1), encoding="utf-8")
    ok =not failures and totals["failures"] == 0 and totals["errors"] == 0
    summary = (f"{'PASS' if ok else 'FAIL'}: {len(batch_rows)}/{len(batches)} batches, {len(executed)}/{len(classes)} classes, tests {totals['tests']}, failures {totals['failures']}, "
               f"errors {totals['errors']}, skipped {totals['skipped']}, min free {min_free} MB, {detail['seconds']}s "
               f"({detail['differenceFromBaselineSeconds']:+d}s against the {BASELINE_SECONDS}s baseline), seconds per batch {[r['seconds'] for r in batch_rows]}"
               + ("; " + " | ".join(failures) if failures else ""))
    return Result(ok, summary, detail)
