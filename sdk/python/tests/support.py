"""Shared helpers for the contract tests. Standard library only (the gate runs them with unittest)."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
REPO = PACKAGE_ROOT.parents[1]
CONTRACTS = REPO / "contracts"
FIXTURES = CONTRACTS / "fixtures" / "firmware-v1"

if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

BOOT = "import sys; sys.path.insert(0, sys.argv.pop(1)); from nexus_contract.cli import main; raise SystemExit(main(sys.argv[1:]))"


def manifest() -> dict:
    return json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))


def fixture_bytes(entry: dict) -> bytes:
    return (FIXTURES / entry["file"]).read_bytes()


def with_version(entry: dict, version: str = "1.0") -> bytes:
    """The fixture with a top-level contractVersion added (status has no JSON body and is returned unchanged)."""
    raw = fixture_bytes(entry)
    if entry["kind"] == "status":
        return raw
    try:
        data = json.loads(raw.decode("utf-8"))
    except ValueError:
        return raw  # a deliberately malformed document stays malformed
    return json.dumps({"contractVersion": version, **data}, separators=(",", ":")).encode("utf-8")


def run_cli(*args: str, stdin: bytes | None = None, extra_boot: str | None = None) -> subprocess.CompletedProcess:
    """Runs the CLI exactly as a user would: a separate isolated interpreter (-I)."""
    boot = (extra_boot + "; " if extra_boot else "") + BOOT
    return subprocess.run([sys.executable, "-I", "-c", boot, str(PACKAGE_ROOT), *args], input=stdin,
                          capture_output=True, timeout=60, check=False)
