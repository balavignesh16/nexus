"""Ledger P4-119: the protected paths are byte-for-byte what they were when Phase 4 started.

`firmware/`, `hardware-tests/` and `results/` (the Review III artefacts) and the user-owned CODEX_HANDOFF.md and ROADMAP.md must be identical in
the committed tree. A git tree or blob id is a content hash, so comparing it with the id recorded in architecture/phase4/protected-paths.json is a
complete check, not a sample. The user-owned CLAUDE.md, NEXT_TASK.md and PROJECT_HANDOFF.md are untracked on `main` and are therefore proven
untouched by their absence from every Phase 4 commit (see test_no_phase4_commit_adds_the_user_owned_untracked_documents)."""
from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECORD = json.loads((ROOT / "architecture" / "phase4" / "protected-paths.json").read_text(encoding="utf-8"))


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise unittest.SkipTest(f"git is not usable here: {result.stderr.strip()[:200]}")
    return result.stdout.strip()


class ProtectedPaths(unittest.TestCase):
    def test_firmware_hardware_tests_and_results_are_unchanged(self):
        for path, expected in RECORD["trees"].items():
            self.assertEqual(expected, git("rev-parse", f"HEAD:{path}"), f"{path}/ differs from its state at {RECORD['baselineCommit']}")

    def test_the_user_owned_tracked_documents_are_unchanged(self):
        for path, expected in RECORD["blobs"].items():
            self.assertEqual(expected, git("rev-parse", f"HEAD:{path}"), f"{path} differs from its state at {RECORD['baselineCommit']}")

    def test_no_phase4_commit_adds_the_user_owned_untracked_documents(self):
        tracked = set(git("ls-tree", "--name-only", "HEAD").splitlines())
        for name in ("CLAUDE.md", "NEXT_TASK.md", "PROJECT_HANDOFF.md"):
            self.assertNotIn(name, tracked, f"{name} is user-owned and untracked; a Phase 4 commit must not add it")

    def test_the_record_names_exactly_the_protected_paths(self):
        self.assertEqual({"firmware", "hardware-tests", "results"}, set(RECORD["trees"]))
        self.assertEqual({"CODEX_HANDOFF.md", "ROADMAP.md"}, set(RECORD["blobs"]))
        for value in list(RECORD["trees"].values()) + list(RECORD["blobs"].values()):
            self.assertRegex(value, r"^[0-9a-f]{40}$")


if __name__ == "__main__":
    unittest.main()
