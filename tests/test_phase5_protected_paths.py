"""Ledger P5-125: the protected paths are byte-for-byte what they were, in EVERY Phase 5 commit and not only at the tip.

`firmware/`, `hardware-tests/` and `results/` (the Review III artefacts) and the user-owned CODEX_HANDOFF.md and ROADMAP.md must be identical to the
ids recorded in architecture/phase4/protected-paths.json (a git tree or blob id is a content hash). The user-owned CLAUDE.md, NEXT_TASK.md and
PROJECT_HANDOFF.md are untracked on `main`, so they are proven untouched by their absence from every commit. Historic Phase 4 commits are never
rewritten: the Phase 4 baseline commits stay ancestors of HEAD."""
from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECORD = json.loads((ROOT / "architecture" / "phase4" / "protected-paths.json").read_text(encoding="utf-8"))
PHASE4_VALIDATED = "e29e215e5138de4460e9891e05859a61a098c3aa"
USER_OWNED_UNTRACKED = ("CLAUDE.md", "NEXT_TASK.md", "PROJECT_HANDOFF.md")


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        raise unittest.SkipTest(f"git is not usable here: {result.stderr.strip()[:200]}")
    return result.stdout.strip()


class ProtectedPathsInEveryPhase5Commit(unittest.TestCase):
    def commits(self) -> list[str]:
        listed = git("rev-list", f"{RECORD['baselineCommit']}..HEAD").splitlines()
        self.assertGreater(len(listed), 0, "there is at least one commit after the Phase 4 baseline")
        return listed

    def test_every_commit_after_the_baseline_has_the_baseline_trees_and_blobs(self):
        for commit in self.commits():
            for path, expected in RECORD["trees"].items():
                self.assertEqual(expected, git("rev-parse", f"{commit}:{path}"), f"{path}/ differs in {commit[:9]}")
            for path, expected in RECORD["blobs"].items():
                self.assertEqual(expected, git("rev-parse", f"{commit}:{path}"), f"{path} differs in {commit[:9]}")

    def test_the_working_tree_has_no_uncommitted_change_in_a_protected_path(self):
        paths = list(RECORD["trees"]) + list(RECORD["blobs"])
        self.assertEqual("", git("status", "--porcelain", "--untracked-files=all", "--", *paths), "a protected path is modified or has untracked files")

    def test_no_commit_adds_or_touches_a_user_owned_untracked_document(self):
        touched = set(git("log", f"{RECORD['baselineCommit']}..HEAD", "--name-only", "--pretty=format:").splitlines())
        for name in USER_OWNED_UNTRACKED:
            self.assertNotIn(name, touched, f"{name} is user-owned and untracked; no commit may add it")
        tracked = set(git("ls-tree", "--name-only", "HEAD").splitlines())
        for name in USER_OWNED_UNTRACKED:
            self.assertNotIn(name, tracked)

    def test_phase4_history_is_not_rewritten(self):
        for commit in (RECORD["baselineCommit"], PHASE4_VALIDATED):
            self.assertEqual(0, subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], cwd=ROOT).returncode,
                             f"{commit[:9]} must stay an ancestor of HEAD")

    def test_the_hardware_evidence_and_review_artefacts_are_not_modified_by_any_commit(self):
        for path in ("firmware", "hardware-tests", "results"):
            changed = git("diff", "--name-only", RECORD["baselineCommit"], "HEAD", "--", path)
            self.assertEqual("", changed, f"{path}/ changed since the Phase 4 baseline")


if __name__ == "__main__":
    unittest.main()
