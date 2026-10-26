"""Golden firmware fixtures, both directions (P5-021, P5-022), frozen corpus, and the source drift detector
(adapter-contract.md section 13). The firmware and simulator are only READ."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import unittest

from tests import support
from nexus_contract import loader
from nexus_contract.validator import validate

FIRMWARE = support.REPO / "firmware" / "src" / "main.cpp"
SIMULATOR = support.REPO / "simulator" / "src" / "nexus_simulator" / "mqtt_publisher.py"


def _expected(entry: dict, mode: str):
    verdict = entry["expect"][mode]
    return verdict[0], (verdict[1] if len(verdict) > 1 else None)


def _run(entry: dict, payload: bytes, **extra):
    return validate(entry["kind"], payload, topic_device_id=entry.get("topicDeviceId"), **extra)


class GoldenFixtures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = support.manifest()

    def test_p5_021_every_fixture_has_the_expected_verdict_as_legacy_v1_and_as_1_0(self):
        for entry in self.manifest["fixtures"]:
            for mode, payload in (("legacy-v1", support.fixture_bytes(entry)), ("1.0", support.with_version(entry))):
                with self.subTest(fixture=entry["id"], mode=mode):
                    result = _run(entry, payload)
                    verdict, code = _expected(entry, mode)
                    self.assertEqual((verdict, code), (result.verdict, result.code))
                    if mode == "1.0" and entry["kind"] != "status" and result.code != "MALFORMED_JSON":
                        self.assertEqual("1.0", result.version)

    def test_p5_154_profile_expectations(self):
        for entry in self.manifest["fixtures"]:
            if entry["kind"] != "command" or "profile" not in entry["expect"]:
                continue
            with self.subTest(fixture=entry["id"]):
                result = validate("command", support.fixture_bytes(entry), profile=entry["profile"], reference_time=entry["referenceTime"])
                verdict, code = entry["expect"]["profile"][0], (entry["expect"]["profile"][1:] or [None])[0]
                self.assertEqual((verdict, code), (result.verdict, result.code))

    def test_command_fixtures_pass_the_profile_with_a_fresh_reference_time(self):
        for entry in self.manifest["fixtures"]:
            if entry["kind"] == "command" and entry["expect"].get("profile", ["ACCEPT"])[0] == "ACCEPT" and entry["expect"]["legacy-v1"][0] == "ACCEPT":
                result = validate("command", support.fixture_bytes(entry), profile=entry["profile"], reference_time=entry["referenceTime"])
                self.assertEqual("ACCEPT", result.verdict, entry["id"])
                self.assertEqual("PASS", result.layer("freshness").status)

    def test_reject_fixtures_exist_for_every_kind_and_both_directions_are_covered(self):
        by_kind = {}
        for entry in self.manifest["fixtures"]:
            by_kind.setdefault(entry["kind"], set()).add(entry["expect"]["legacy-v1"][0])
        for kind in ("telemetry", "acknowledgement", "status", "command"):
            self.assertEqual({"ACCEPT", "REJECT"}, by_kind[kind], kind)

    def test_fixtures_are_frozen_by_content_hash(self):
        listed = set()
        for entry in self.manifest["fixtures"]:
            listed.add(entry["file"])
            digest = hashlib.sha256(support.fixture_bytes(entry)).hexdigest()
            self.assertEqual(entry["sha256"], digest, f"{entry['id']} changed: fixtures are extended only by addition")
        on_disk = {path.name for path in support.FIXTURES.iterdir() if path.name != "manifest.json"}
        self.assertEqual(on_disk, listed, "every fixture file must be listed with provenance, and vice versa")

    def test_every_fixture_records_provenance(self):
        for entry in self.manifest["fixtures"]:
            self.assertTrue(entry.get("origin"), entry["id"])
            if entry["origin"] in ("firmware", "simulator"):
                self.assertTrue(entry.get("source", {}).get("anchor"), f"{entry['id']} needs a source anchor")
        baseline = self.manifest["baseline"]
        self.assertRegex(baseline["firmwareCommit"], r"^[0-9a-f]{40}$")

    def test_exact_bytes_are_compact_json_as_the_firmware_serialises_them(self):
        for entry in self.manifest["fixtures"]:
            if entry["origin"] == "firmware" and entry["kind"] in ("telemetry", "acknowledgement"):
                text = support.fixture_bytes(entry).decode("utf-8")
                self.assertNotIn(" ", text.replace("TEMPERATURE_SENSOR", "").replace("CELSIUS", ""), entry["id"])
                self.assertEqual(text, json.dumps(json.loads(text), separators=(",", ":")), entry["id"])


class SourceDrift(unittest.TestCase):
    """Parses the firmware source for the wire facts and compares them with the fixtures and the contract. If the firmware
    changes any of these, the test fails and names the fact; the firmware tree is separately pinned (protected-paths.json)."""

    @classmethod
    def setUpClass(cls):
        cls.source = FIRMWARE.read_text(encoding="utf-8")
        cls.manifest = support.manifest()

    def test_firmware_source_is_the_baseline_blob(self):
        digest = subprocess.run(["git", "hash-object", str(FIRMWARE)], capture_output=True, text=True, cwd=support.REPO, check=True).stdout.strip()
        self.assertEqual(self.manifest["baseline"]["firmwareSourceBlob"], digest,
                         "firmware/src/main.cpp changed: regenerate and review the fixtures (a reviewed contract change)")

    def test_telemetry_keys_match_the_fixture(self):
        keys = re.findall(r'\bdoc\["(\w+)"\]\s*=', self.source)
        self.assertEqual(["deviceId", "timestamp", "sensorType", "value", "unit"], keys)
        fixture = next(e for e in self.manifest["fixtures"] if e["id"] == "telemetry-temperature")
        self.assertEqual(keys, list(json.loads(support.fixture_bytes(fixture))))

    def test_acknowledgement_keys_match_the_fixtures(self):
        keys = re.findall(r'\backDoc\["(\w+)"\]\s*=', self.source)
        self.assertEqual(["commandId", "status", "timestamp", "reason"], keys)
        accepted = json.loads(support.fixture_bytes(next(e for e in self.manifest["fixtures"] if e["id"] == "ack-accepted")))
        rejected = json.loads(support.fixture_bytes(next(e for e in self.manifest["fixtures"] if e["id"] == "ack-rejected-stale")))
        self.assertEqual(keys[:3], list(accepted))
        self.assertEqual(keys, list(rejected))

    def test_command_fields_and_types_the_firmware_requires(self):
        required = dict(re.findall(r'doc\["(\w+)"\]\.is<([\w\s*]+)>\(\)', self.source))
        self.assertEqual({"commandId": "const char*", "capability": "const char*", "command": "const char*", "value": "bool", "timestamp": "long"},
                         {k: v.strip() for k, v in required.items()})

    def test_command_allowlist_window_and_rule_match_the_profile(self):
        profile = loader.versions()["profiles"]["esp32-legacy-v1"]
        self.assertEqual(profile["capability"], re.search(r'strcmp\(capability, "(\w+)"\)', self.source).group(1))
        self.assertEqual(profile["supportedCommand"], re.search(r'strcmp\(command, "(\w+)"\)', self.source).group(1))
        window = int(re.search(r"COMMAND_STALENESS_WINDOW_SECONDS\s*=\s*(\d+)L", self.source).group(1))
        self.assertEqual(profile["commandFreshnessWindowSeconds"], window)
        self.assertIn("if (age < 0)", self.source)
        self.assertIn("age = -age", self.source, "the freshness rule must still be the absolute difference")
        self.assertIn("age > COMMAND_STALENESS_WINDOW_SECONDS", self.source)

    def test_ack_reasons_and_statuses_and_status_texts(self):
        self.assertEqual({"MALFORMED", "UNSUPPORTED", "STALE"}, set(re.findall(r'publishAck\(commandId, "REJECTED", "(\w+)"\)', self.source)))
        self.assertIn('publishAck(commandId, "ACCEPTED", nullptr)', self.source)
        self.assertIn('"online"', self.source)
        self.assertIn('"offline"', self.source)

    def test_topics_have_the_documented_shape(self):
        self.assertEqual(['"/telemetry"', '"/status"', '"/commands"'],
                         [m for m in re.findall(r'NEXUS_DEVICE_UUID \+ ("/\w+")', self.source)])
        self.assertIn('deviceCommandTopic + "/ack"', self.source)

    def test_timestamp_format_is_seconds_precision_zulu(self):
        self.assertIn('"%Y-%m-%dT%H:%M:%SZ"', self.source)

    def test_every_source_anchor_still_exists(self):
        for entry in self.manifest["fixtures"]:
            source = entry.get("source")
            if not source:
                continue
            text = (support.REPO / source["file"]).read_text(encoding="utf-8")
            self.assertIn(source["anchor"], text, f"{entry['id']}: anchor no longer present in {source['file']}")

    def test_simulator_payload_keys_are_covered_by_the_schema(self):
        text = SIMULATOR.read_text(encoding="utf-8")
        block = next(b for b in text.split("payload = {")[1:] if '"deviceId"' in b.split("}", 1)[0])
        keys = set(re.findall(r'"(\w+)":\s', block.split("}", 1)[0]))
        self.assertIn("deviceId", keys)
        schema = loader.schema(loader.versions()["kinds"]["telemetry"])
        self.assertLessEqual(keys, set(schema["properties"]))


if __name__ == "__main__":
    unittest.main()
