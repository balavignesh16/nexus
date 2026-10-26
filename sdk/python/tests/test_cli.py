"""The local validator command line (P5-004): no network, no database, no broker; fixed codes only."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tests import support

D = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13"


class CommandLine(unittest.TestCase):
    def test_p5_004_accepts_a_fixture_and_exits_zero(self):
        done = support.run_cli("validate", "--kind", "telemetry", str(support.FIXTURES / "telemetry-temperature.json"), "--topic", f"nexus/devices/{D}/telemetry")
        self.assertEqual(0, done.returncode, done.stderr)
        out = done.stdout.decode()
        self.assertIn("ACCEPT kind=telemetry version=legacy-v1", out)
        self.assertIn("identity    PASS", out)

    def test_p5_004_rejects_with_a_fixed_code_and_never_echoes_payload_content(self):
        marker = "SECRET-MARKER-9f3a"
        payload = json.dumps({"deviceId": D, "timestamp": "2026-01-01T00:00:00Z", "sensorType": marker, "value": "oops", "unit": "U"}).encode()
        done = support.run_cli("validate", "--kind", "telemetry", "-", stdin=payload)
        self.assertEqual(1, done.returncode)
        self.assertIn("INVALID_LEGACY_PAYLOAD", done.stdout.decode())
        self.assertNotIn(marker, done.stdout.decode() + done.stderr.decode())

    def test_p5_004_runs_with_the_network_disabled(self):
        offline = "import socket; socket.socket.__init__ = lambda *a, **k: (_ for _ in ()).throw(RuntimeError('network use'))"
        done = support.run_cli("validate", "--kind", "status", "-", stdin=b"online", extra_boot=offline)
        self.assertEqual(0, done.returncode, done.stderr)

    def test_p5_004_imports_no_network_database_or_broker_modules(self):
        probe = ("import sys; sys.path.insert(0, sys.argv.pop(1)); import nexus_contract.cli; "
                 "bad = [m for m in ('socket','http','urllib.request','sqlite3','ssl','asyncio','paho','psycopg2','requests') if m in sys.modules]; "
                 "raise SystemExit(1 if bad else 0)")
        import subprocess, sys
        done = subprocess.run([sys.executable, "-I", "-c", probe, str(support.PACKAGE_ROOT)], capture_output=True, timeout=60)
        self.assertEqual(0, done.returncode, done.stderr)

    def test_json_output_and_options(self):
        done = support.run_cli("validate", "--kind", "command", "--profile", "esp32-legacy-v1", "--reference-time", "1767225600", "--json",
                               str(support.FIXTURES / "command-led-on.json"))
        self.assertEqual(0, done.returncode, done.stderr)
        result = json.loads(done.stdout)
        self.assertEqual("ACCEPT", result["verdict"])
        self.assertEqual("PASS", next(x for x in result["layers"] if x["layer"] == "freshness")["status"])

    def test_p5_155_require_all_layers_fails_on_not_performed(self):
        path = str(support.FIXTURES / "command-led-on.json")
        self.assertEqual(0, support.run_cli("validate", "--kind", "command", path).returncode)
        self.assertEqual(1, support.run_cli("validate", "--kind", "command", "--require-all-layers", path).returncode)

    def test_stale_command_exits_nonzero(self):
        done = support.run_cli("validate", "--kind", "command", "--profile", "esp32-legacy-v1", "--reference-time", "1767225631",
                               str(support.FIXTURES / "command-led-on.json"))
        self.assertEqual(1, done.returncode)
        self.assertIn("STALE", done.stdout.decode())

    def test_usage_errors_exit_two(self):
        self.assertEqual(2, support.run_cli("validate", "--kind", "telemetry", "does-not-exist.json").returncode)
        self.assertEqual(2, support.run_cli("validate", "--kind", "bogus", "-", stdin=b"{}").returncode)
        self.assertEqual(2, support.run_cli("validate", "--kind", "command", "--reference-time", "yesterday", "-", stdin=b"{}").returncode)
        self.assertEqual(2, support.run_cli().returncode)

    def test_contract_version_and_legacy_options(self):
        body = json.dumps({"deviceId": D, "timestamp": "2026-01-01T00:00:00Z", "sensorType": "S", "value": 1, "unit": "U"}).encode()
        self.assertEqual(0, support.run_cli("validate", "--kind", "telemetry", "--contract-version", "1.0", "-", stdin=body).returncode)
        self.assertEqual(1, support.run_cli("validate", "--kind", "telemetry", "--contract-version", "2.0", "-", stdin=body).returncode)
        versioned = json.dumps({**json.loads(body), "contractVersion": "7.7"}).encode()
        self.assertEqual(1, support.run_cli("validate", "--kind", "telemetry", "-", stdin=versioned).returncode)
        self.assertEqual(0, support.run_cli("validate", "--kind", "telemetry", "--legacy", "-", stdin=versioned).returncode)

    def test_validate_corpus_validates_a_whole_manifest_in_one_process(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out.json"
            done = support.run_cli("validate-corpus", "--manifest", str(support.CONTRACTS / "parity" / "corpus.json"), "--output", str(out))
            self.assertEqual(0, done.returncode, done.stderr)
            results = json.loads(out.read_text())["results"]
            corpus = json.loads((support.CONTRACTS / "parity" / "corpus.json").read_text())["items"]
            self.assertEqual({item["id"] for item in corpus}, set(results))

    def test_versions_subcommand_prints_the_support_window(self):
        done = support.run_cli("versions")
        self.assertEqual([1], json.loads(done.stdout)["supportedMajors"])


if __name__ == "__main__":
    unittest.main()
