#!/usr/bin/env python3
"""Deterministic Phase 0 validation entry point."""
from __future__ import annotations
import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
import nexus_runtime as rt
import backend_batches

@dataclass
class Check:
    name: str
    status: str
    startedAt: str
    durationMs: int
    reason: str
    outputSummary: str = ""

BACKEND_FULL_TIMEOUT_SECONDS = 1800


def cli() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Validate the NEXUS repository")
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--fast", action="store_true")
    mode.add_argument("--full", action="store_true")
    p.add_argument("--verbose", action="store_true")
    return p

def execute(name: str, command: list[str], cwd: Path, timeout: int, verbose: bool) -> Check:
    started, stamp = time.monotonic(), rt.utc_now()
    try:
        result = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                                timeout=timeout, check=False)
        output = (result.stdout or "") + (result.stderr or "")
        status = "PASS" if result.returncode == 0 else "FAIL"
        reason = "completed successfully" if status == "PASS" else f"exit code {result.returncode}"
    except subprocess.TimeoutExpired as error:
        output, status, reason = (error.stdout or "") + (error.stderr or ""), "FAIL", f"timed out after {timeout}s"
    if verbose or status == "FAIL":
        print(f"\n--- {name} ---\n{str(output)[-4000:]}")
    return Check(name, status, stamp, int((time.monotonic() - started) * 1000), reason, str(output)[-2000:])

def backend_batches_check(mvnw: str, verbose: bool) -> Check:
    started, stamp = time.monotonic(), rt.utc_now()
    try:
        result = backend_batches.run(mvnw, BACKEND_FULL_TIMEOUT_SECONDS, verbose, log_dir=rt.VALIDATION_DIR)
    except Exception as error:                                   # inventory or orchestration fault: a failed check, never a silent pass
        return Check("complete backend and integration suite", "FAIL", stamp, int((time.monotonic() - started) * 1000), f"batch orchestration error: {error}")
    status = "PASS" if result.ok else "FAIL"
    if verbose or not result.ok:
        print("\n--- complete backend and integration suite ---\n" + result.summary)
    return Check("complete backend and integration suite", status, stamp, int((time.monotonic() - started) * 1000), result.summary, result.summary)


def skipped(name: str, reason: str) -> Check:
    return Check(name, "SKIP", rt.utc_now(), 0, reason)

def main() -> int:
    a = cli().parse_args()
    validation_started = rt.utc_now()
    if a.full:
        collisions = []
        if rt.live_orchestrator():
            collisions.append("active orchestrator")
        for name in ("backend", "frontend"):
            if rt.port_open(rt.PORTS[name]):
                collisions.append(f"{name} port {rt.PORTS[name]}")
        if collisions:
            print("[NEXUS] full validation requires a stopped application environment: " + ", ".join(collisions))
            print("[NEXUS] run: python stop_nexus.py --infra")
            return 2
    checks: list[Check] = []
    py = sys.executable
    checks.append(execute("repository tooling tests", [py, "-m", "unittest", "discover", "-s", "tests", "-v"], rt.ROOT, 180, a.verbose))
    checks.append(execute("Python tooling syntax", [py, "-m", "py_compile", "start_nexus.py", "stop_nexus.py",
                                                   "stop_script.py", "validate_nexus.py", "nexus_runtime.py", "phase4_enablement_check.py", "backend_batches.py"], rt.ROOT, 60, a.verbose))
    mvnw = str(rt.ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    if a.full:
        # Wall-clock budget of the complete backend suite only (real PostgreSQL and Mosquitto containers, 190+ test classes). The suite measured
        # 1,068 s at P4.3 and 1,175 s at P4.4 on the 16 GB development machine, so the former 1,200 s left a 25 s margin and made the gate flaky for reasons
        # unrelated to correctness; 1,800 s leaves a margin of about 50%. No check, assertion, skip or exit-code handling was changed, and no other budget.
        # P4-FINAL: the same set of test classes, run as deterministic sequential bounded batches (backend_batches.py) because one Maven process holding every
        # container and Spring context exhausted the machine's memory. The 1,800 s budget is unchanged and covers all batches; completeness is proven from the reports.
        checks.append(backend_batches_check(mvnw, a.verbose))
    else:
        checks.append(execute("backend compile", [mvnw, "-q", "-DskipTests", "test"], rt.ROOT / "backend", 300, a.verbose))
    npm = "npm.cmd" if os.name == "nt" else "npm"
    checks.append(execute("frontend lint", [npm, "run", "lint"], rt.ROOT / "frontend", 300, a.verbose))
    checks.append(execute("frontend unit tests", [npm, "test", "--", "--run"], rt.ROOT / "frontend", 600, a.verbose))
    checks.append(execute("frontend production build", [npm, "run", "build"], rt.ROOT / "frontend", 600, a.verbose))
    simpy = rt.ROOT / "simulator/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if simpy.exists():
        checks.append(execute("simulator unit tests", [str(simpy), "-m", "pytest", "-q"], rt.ROOT / "simulator", 300, a.verbose))
    else:
        checks.append(skipped("simulator unit tests", f"environment not installed at {simpy}"))
    checks.append(execute("git whitespace check", ["git", "diff", "--check"], rt.ROOT, 60, a.verbose))
    if a.full:
        checks.append(execute("deterministic physical-intelligence loop smoke",
                              [mvnw, "-q", "-Dtest=PhysicalIntelligenceLoopSmokeTest", "test"],
                              rt.ROOT / "backend", 600, a.verbose))
        # Existing isolated Testcontainers tests exercise fresh migrations, persistence,
        # MQTT publish/dispatch/ACK mechanics, Twin/rule hydration, and restart behavior.
        checks.append(execute("isolated MQTT command/ACK smoke",
                              [mvnw, "-q", "-Dtest=CommandDispatchMqttIntegrationTest", "test"],
                              rt.ROOT / "backend", 600, a.verbose))
        checks.append(execute("isolated database restart/persistence smoke",
                              [mvnw, "-q", "-Dtest=InMemoryDigitalTwinRegistryTest,RuleRegistryPersistenceIntegrationTest", "test"],
                              rt.ROOT / "backend", 600, a.verbose))
        # Phase 4 (P4-FINAL, ledger P4-116): real backend processes are started against disposable PostgreSQL and Mosquitto, stopped without any
        # graceful shutdown at chosen points, and replaced; the assertions are made on durable rows and on what the broker itself received. The
        # tagged tests are excluded from the ordinary suite above (pom: surefire.excludedGroups) and run here explicitly. The smoke is three
        # scenarios (kill after acceptance and a standby takes over; kill after publish and recovery stays uncertain without a duplicate; an ACK
        # timeout that passed during downtime is persisted after the restart); the complete campaign is `-Dsurefire.groups=crash`.
        checks.append(execute("phase 4 crash-recovery smoke (real processes killed and restarted)",
                              [mvnw, "-q", "-Dsurefire.groups=crash-smoke", "-Dsurefire.excludedGroups=", "-Dtest=CommandCrashRecoveryTest", "test"],
                              rt.ROOT / "backend", 1200, a.verbose))
        # The enablement and rollback checklist (contract R22) as a script, run against a freshly migrated disposable database.
        checks.append(execute("phase 4 enablement procedure check (script against a migrated database)",
                              [mvnw, "-q", "-Dsurefire.groups=crash", "-Dsurefire.excludedGroups=", "-Dtest=CommandEnablementProcedureTest", "test"],
                              rt.ROOT / "backend", 600, a.verbose))
    report = {
        "schemaVersion": 1, "mode": "full" if a.full else "fast",
        "startedAt": validation_started, "finishedAt": rt.utc_now(),
        "overall": "PASS" if all(c.status in {"PASS", "KNOWN_FAIL", "SKIP"} for c in checks) else "FAIL",
        "environment": {"python": sys.version.split()[0], "repositoryRoot": str(rt.ROOT)},
        "checks": [asdict(c) for c in checks],
        "preExistingCarryovers": [], "newFailures": [c.name for c in checks if c.status == "FAIL"],
    }
    rt.VALIDATION_DIR.mkdir(parents=True, exist_ok=True)
    rt.atomic_json(rt.VALIDATION_DIR / "latest.json", report)
    lines = [f"NEXUS {report['mode']} validation: {report['overall']}"]
    lines.extend(f"{c.status:10} {c.name} ({c.durationMs} ms) - {c.reason}" for c in checks)
    (rt.VALIDATION_DIR / "latest.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 0 if report["overall"] == "PASS" else 1

if __name__ == "__main__":
    raise SystemExit(main())
