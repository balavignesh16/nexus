from __future__ import annotations
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import nexus_runtime as rt

ROOT = Path(__file__).resolve().parents[1]

class RuntimeToolingTests(unittest.TestCase):
    def test_help_has_no_runtime_side_effect(self):
        before = rt.MANIFEST.exists()
        result = subprocess.run([sys.executable, "start_nexus.py", "--help"], cwd=ROOT,
                                capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode)
        self.assertIn("--startup-timeout", result.stdout)
        self.assertEqual(before, rt.MANIFEST.exists())

    def test_atomic_json_is_valid_and_replaces(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "state.json"
            rt.atomic_json(target, {"schemaVersion": 1, "value": "first"})
            rt.atomic_json(target, {"schemaVersion": 1, "value": "second"})
            self.assertEqual("second", json.loads(target.read_text())["value"])
            self.assertEqual([], list(target.parent.glob("*.tmp")))

    def test_reused_service_is_never_terminated_by_stop_contract(self):
        state = {"services": {"backend": {"pid": 123, "owned": False, "reused": True}}}
        self.assertFalse(state["services"]["backend"]["owned"])

    def test_live_orchestrator_lock_cannot_be_stolen(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory)
            manifest = state_dir / "runtime.json"
            lock = state_dir / "runtime.lock"
            payload = {"schemaVersion": 1, "orchestratorPid": 444,
                       "repositoryRoot": str(rt.ROOT), "services": {}}
            rt.atomic_json(manifest, payload)
            with patch.object(rt, "STATE_DIR", state_dir), \
                    patch.object(rt, "MANIFEST", manifest), \
                    patch.object(rt, "LOCK", lock), \
                    patch("nexus_runtime.plausible_process", return_value=True):
                with self.assertRaisesRegex(RuntimeError, "already active"):
                    rt.acquire_lock()
                self.assertFalse(lock.exists())

    def test_stale_lock_is_reconciled_conservatively(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory)
            manifest = state_dir / "runtime.json"
            lock = state_dir / "runtime.lock"
            rt.atomic_json(lock, {"schemaVersion": 1, "pid": 999999,
                                  "repositoryRoot": str(rt.ROOT)})
            with patch.object(rt, "STATE_DIR", state_dir), \
                    patch.object(rt, "MANIFEST", manifest), \
                    patch.object(rt, "LOCK", lock), \
                    patch("nexus_runtime.plausible_process", return_value=False):
                rt.acquire_lock()
                self.assertTrue(lock.exists())
                self.assertEqual(os.getpid(), json.loads(lock.read_text())["pid"])
                rt.release_lock()
                self.assertFalse(lock.exists())

    @patch("nexus_runtime.command_line", return_value="unrelated.exe")
    @patch("nexus_runtime.pid_alive", return_value=True)
    def test_pid_number_alone_is_not_trusted(self, _alive, _line):
        self.assertFalse(rt.plausible_process(42, ["start_nexus.py"]))

    @patch("nexus_runtime.http_text", return_value="<html><title>Other</title></html>")
    def test_frontend_identity_rejects_unrelated_http(self, _http):
        self.assertFalse(rt.nexus_frontend())

    @patch("nexus_runtime.http_text", return_value='{"status":"UP"}')
    def test_backend_identity_rejects_unrelated_http(self, _http):
        self.assertFalse(rt.nexus_backend())

if __name__ == "__main__":
    unittest.main()
