"""The ESP32 helper library (sdk/esp32), ledger P5-085 and P5-093 to P5-101.

The library is C++ and is built and run on the host with g++ (C++11, warnings as errors) against the repository's golden files. This
module drives that build and then judges what the library produced with the Python contract validator and the Python device SDK, so the
library is compared with an independent implementation rather than only with its own expectations. Nothing here runs on an ESP32 and
nothing here is a hardware-validation claim. When no C++ compiler is installed the build-dependent tests are SKIPPED, loudly.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace

from tests import support
from nexus_contract import rules
from nexus_contract.validator import validate
from nexus_device_sdk import DeviceClient, InMemoryTransport, is_fresh

LIBRARY = support.REPO / "sdk" / "esp32"
HOST = LIBRARY / "test" / "host"
DEVICE = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13"
COMMAND_ID = "a1b2c3d4-e5f6-4789-8abc-def012345678"
NOW = 1_767_225_600
FIRMWARE_TREE = "bda5d9bc20574660d87b5cf30004ef0203176fbd"


def _build_module():
    sys.path.insert(0, str(LIBRARY / "test"))
    try:
        import build_host
        return build_host
    finally:
        sys.path.pop(0)


def _golden() -> list[tuple[str, int, bytes, str]]:
    rows = []
    for line in (LIBRARY / "test" / "golden" / "commands.tsv").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        case_id, now, hex_payload, expected = line.split("\t")
        rows.append((case_id, int(now), b"" if hex_payload == "-" else bytes.fromhex(hex_payload), expected))
    return rows


def _safe(case_id: str) -> str:
    return case_id.replace(":", "_")


class LayoutAndIsolation(unittest.TestCase):
    def test_p5_093_the_layout_is_platformio_and_arduino_compatible(self):
        library = json.loads((LIBRARY / "library.json").read_text(encoding="utf-8"))
        self.assertEqual("src", library["build"]["srcDir"])
        self.assertIn("arduino", library["frameworks"])
        self.assertIn("espressif32", library["platforms"])
        self.assertRegex(library["version"], r"^\d+\.\d+\.\d+$")
        properties = dict(line.split("=", 1) for line in (LIBRARY / "library.properties").read_text(encoding="utf-8").splitlines() if "=" in line)
        self.assertEqual(library["version"], properties["version"], "the two manifests agree")
        self.assertEqual("NexusSdk.h", properties["includes"])
        self.assertTrue((LIBRARY / "src" / "NexusSdk.h").is_file())
        sketches = sorted(p.relative_to(LIBRARY).as_posix() for p in (LIBRARY / "examples").glob("*/*.ino"))
        self.assertEqual(["examples/LedCommandNode/LedCommandNode.ino", "examples/TemperatureNode/TemperatureNode.ino"], sketches)
        for example in library["examples"]:  # each declared example exists, in a folder named like its sketch (an Arduino rule)
            for name in example["files"]:
                self.assertTrue((LIBRARY / example["base"] / name).is_file(), example)
                self.assertEqual(Path(name).stem, Path(example["base"]).name)
        ini = (LIBRARY / "platformio.ini").read_text(encoding="utf-8")
        self.assertIn("[env:native]", ini)
        self.assertIn("platform = native", ini)
        # the library's headers need no platform include; only the Arduino glue and the sketches do
        for header in (LIBRARY / "src" / "nexus").glob("*"):
            text = header.read_text(encoding="utf-8")
            self.assertNotIn("Arduino.h", text, header.name)
            self.assertNotIn("WiFi", text, header.name)

    def test_p5_094_the_firmware_is_untouched_and_not_imported(self):
        git = shutil.which("git")
        if git is None:
            self.skipTest("git is not available, so the firmware tree hash cannot be read")
        tree = subprocess.run([git, "-C", str(support.REPO), "rev-parse", "HEAD:firmware"], capture_output=True, text=True, check=True).stdout.strip()
        self.assertEqual(FIRMWARE_TREE, tree, "the firmware tree is byte-identical to the Phase 4 baseline")
        self.assertEqual(support.manifest()["baseline"]["firmwareTreeHash"], tree)
        dirty = subprocess.run([git, "-C", str(support.REPO), "status", "--porcelain", "--", "firmware"], capture_output=True, text=True, check=True).stdout
        self.assertEqual("", dirty.strip(), "no uncommitted change under firmware/")
        for path in LIBRARY.rglob("*"):
            if path.suffix in {".h", ".cpp", ".ino", ".json", ".ini", ".properties"} and path.is_file():
                for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                    if line.lstrip().startswith("#include"):
                        self.assertNotIn("firmware", line, f"{path.name}:{number} imports from firmware/")
                    if path.suffix in {".json", ".ini", ".properties"}:
                        self.assertNotRegex(line, r"(\.\./)+firmware|firmware/src", f"{path.name}:{number}")

    def test_the_golden_command_file_is_current(self):
        result = subprocess.run([sys.executable, str(LIBRARY / "tools" / "gen_golden.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


@unittest.skipIf(_build_module().compiler() is None, "SKIPPED: no C++ compiler (g++) is installed, so the ESP32 library was NOT built or run here")
class HostBuild(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = _build_module()
        cls.out = Path(tempfile.mkdtemp(prefix="nexus-esp32-test-"))
        cls.addClassCleanup(shutil.rmtree, cls.out, True)
        (cls.out / "emit" / "conformance").mkdir(parents=True)
        (cls.out / "emit" / "reference").mkdir(parents=True)
        cls.unit = cls.build.build_unit_tests(cls.out)
        run = subprocess.run([str(cls.unit), str(support.REPO), "--emit", str(cls.out / "emit")], capture_output=True, text=True)
        cls.unit_output, cls.unit_status, cls.unit_stderr = run.stdout, run.returncode, run.stderr
        cls.emit = cls.out / "emit"

    def read(self, name: str) -> bytes:
        return (self.emit / name).read_bytes()

    # ---- P5-093 / P5-100: the host build and the samples

    def test_p5_093_the_host_build_compiles_without_warnings_and_every_unit_test_passes(self):
        self.assertEqual(0, self.unit_status, self.unit_stderr + self.unit_output)
        self.assertRegex(self.unit_output, r"\d+ checks, 0 failures")
        for name in ("freshness rule", "clock gate", "telemetry golden", "message ids", "ack golden", "command corpus", "reference device"):
            self.assertRegex(self.unit_output, rf"{name}\s+ok", name)

    def test_p5_100_each_sample_compiles_on_the_host_against_stubs_and_behaves_as_documented(self):
        for sketch, mode in self.build.SAMPLES:
            with self.subTest(sketch=sketch):
                binary = self.build.build_sample(sketch, self.out)
                run = subprocess.run([str(binary), mode], capture_output=True, text=True)
                self.assertEqual(0, run.returncode, run.stderr + run.stdout)
                self.assertIn(f"{mode} sample: ok", run.stdout)

    # ---- P5-095: telemetry

    def test_p5_095_the_telemetry_the_library_builds_is_accepted_by_the_validator_and_parses_to_the_golden_values(self):
        legacy = self.read("telemetry-legacy.json")
        self.assertEqual((support.FIXTURES / "telemetry-temperature.json").read_bytes(), legacy, "legacy form equals the firmware fixture byte for byte")
        for name in ("telemetry-legacy.json", "telemetry-versioned.json", "telemetry-readback.json", "telemetry-boolean.json", "telemetry-integer.json"):
            with self.subTest(name=name):
                result = validate("telemetry", self.read(name), legacy=name.endswith("legacy.json"), topic_device_id=DEVICE)
                self.assertEqual("ACCEPT", result.verdict, result)
        golden = json.loads((support.FIXTURES / "telemetry-temperature.json").read_text(encoding="utf-8"))
        built = json.loads(self.read("telemetry-versioned.json"))
        self.assertEqual("1.0", built.pop("contractVersion"))
        self.assertTrue(rules.is_uuid(built.pop("messageId")))
        self.assertEqual(golden, built, "the typed values equal the fixture's")
        typed = json.loads(self.read("telemetry-integer.json"))
        self.assertEqual((7, "COUNT"), (typed["value"], typed["unit"]))
        self.assertIs(json.loads(self.read("telemetry-boolean.json"))["value"], True)

    def test_p5_095_the_library_and_the_python_sdk_build_the_same_bytes(self):
        message_id = json.loads(self.read("telemetry-versioned.json"))["messageId"]
        client = DeviceClient(DEVICE, InMemoryTransport(), clock=lambda: NOW)
        python_message = client.build_telemetry("TEMPERATURE_SENSOR", 24.5, "CELSIUS", message_id=message_id)
        self.assertEqual(python_message.payload, self.read("telemetry-versioned.json"))
        readback = client.build_telemetry("LED", True, "BOOLEAN", message_id=json.loads(self.read("telemetry-readback.json"))["messageId"],
                                          capability_key="led", readback_command_id=COMMAND_ID)
        self.assertEqual(readback.payload, self.read("telemetry-readback.json"))

    # ---- P5-096: message ids

    def test_p5_096_message_ids_are_stable_for_a_sample_and_unique_across_samples(self):
        self.assertTrue(rules.is_uuid(self.read("message-id-sample.txt").decode()))
        one, two, three = (json.loads(self.read(f"reference/sample-{n}.json")) for n in (1, 2, 3))
        ids = [one["messageId"], two["messageId"], three["messageId"]]
        self.assertEqual(3, len(set(ids)), "three samples, three ids (the retried sample reused its id inside the C++ test)")
        for message_id in ids:
            self.assertEqual(4, uuid.UUID(message_id).version)

    # ---- P5-097 / P5-101: the command corpus

    def test_p5_097_p5_101_the_reference_device_acknowledges_exactly_what_the_corpus_says(self):
        rows = _golden()
        self.assertEqual(len(rows), int(self.read("conformance/cases.txt")))
        self.assertGreaterEqual(len(rows), 100)
        acknowledged = 0
        for case_id, now, payload, expected in rows:
            with self.subTest(case=case_id):
                path = self.emit / "conformance" / (_safe(case_id) + ".ack")
                if expected.startswith("NO_ACK"):
                    self.assertFalse(path.exists(), "no acknowledgement for a command without a usable commandId or a trustworthy clock")
                    continue
                acknowledged += 1
                ack = path.read_bytes()
                result = validate("acknowledgement", ack)
                self.assertEqual("ACCEPT", result.verdict, result)
                body = json.loads(ack)
                status, _, reason = expected.partition(":")
                self.assertEqual(status, body["status"])
                self.assertEqual(reason or None, body.get("reason"))
                self.assertEqual(now, body["timestamp"])
                self.assertEqual(rules.parse_json(payload)["commandId"], body["commandId"], "the acknowledgement names the command it answers")
        self.assertGreater(acknowledged, 60)

    def test_p5_097_the_verdicts_equal_the_contract_validator_with_the_esp32_profile(self):
        for case_id, now, payload, expected in _golden():
            if expected.startswith("NO_ACK"):
                continue
            with self.subTest(case=case_id):
                result = validate("command", payload, profile="esp32-legacy-v1", reference_time=now)
                self.assertEqual("ACCEPT" if expected == "ACCEPTED" else "REJECT", result.verdict, result)
                if expected == "REJECTED:STALE":
                    self.assertEqual("STALE", result.code)
                if expected == "REJECTED:UNSUPPORTED":
                    self.assertEqual("UNSUPPORTED", result.code)

    # ---- P5-098: acknowledgements

    def test_p5_098_acknowledgements_validate_and_equal_the_python_sdk_and_the_firmware_fixtures(self):
        client = DeviceClient(DEVICE, InMemoryTransport(), clock=lambda: NOW)
        for name, status, reason in (("ack-accepted", "ACCEPTED", None), ("ack-stale", "REJECTED", "STALE"), ("ack-unsupported", "REJECTED", "UNSUPPORTED"),
                                     ("ack-malformed", "REJECTED", "MALFORMED"), ("ack-device-rejected", "REJECTED", "DEVICE_REJECTED")):
            with self.subTest(name=name):
                built = self.read(f"{name}.json")
                self.assertEqual("ACCEPT", validate("acknowledgement", built).verdict)
                self.assertEqual(client.build_acknowledgement(COMMAND_ID, status, reason).payload, built)
                self.assertIsInstance(json.loads(built)["timestamp"], int)
        self.assertEqual((support.FIXTURES / "ack-accepted.json").read_bytes(), self.read("ack-legacy-accepted.json"))
        self.assertEqual("ACCEPT", validate("acknowledgement", self.read("ack-uppercase-uuid.json")).verdict)

    # ---- P5-085: freshness agreement

    def test_p5_085_the_library_the_python_sdk_the_simulator_the_validator_and_the_backend_agree_on_freshness(self):
        sys.path.insert(0, str(support.REPO / "simulator" / "src"))
        try:
            from nexus_simulator.actuators import LedActuator
            from nexus_simulator.command_handler import CommandHandler
        finally:
            sys.path.pop(0)
        cases = support.manifest()["freshnessCases"]
        library = {case_id: expected for case_id, _, _, expected in _golden()}
        payload = (support.FIXTURES / (cases["fixture"] + ".json")).read_bytes()
        timestamp = json.loads(payload)["timestamp"]
        for offset in cases["offsetsSeconds"]:
            with self.subTest(offset=offset):
                now = timestamp + offset
                stale = abs(offset) > cases["windowSeconds"]
                expected_library = "REJECTED:STALE" if stale else "ACCEPTED"
                self.assertEqual(expected_library, library[f"freshness:{offset:+d}"], "the C++ library (driven by the golden file in the unit test)")
                self.assertEqual(not stale, is_fresh(now, timestamp), "the Python device SDK")
                profile = validate("command", payload, profile="esp32-legacy-v1", reference_time=now)
                self.assertEqual(stale, profile.code == "STALE", "the contract validator")
                device = SimpleNamespace(write_capabilities={"LED": SimpleNamespace(actuator=LedActuator())})
                simulated = CommandHandler(device, clock=lambda: now).handle(payload)[1:]
                self.assertEqual(("REJECTED", "STALE") if stale else ("ACCEPTED", None), simulated, "the simulator")
        self.assertEqual(sorted(cases["expectStale"]), sorted(o for o in cases["offsetsSeconds"] if abs(o) > cases["windowSeconds"]))

    def test_p5_085_every_statement_of_the_window_in_the_repository_is_thirty_seconds(self):
        header = (LIBRARY / "src" / "nexus" / "Core.h").read_text(encoding="utf-8")
        self.assertEqual("30", re.search(r"kCommandFreshnessWindowSeconds = (\d+);", header).group(1))
        firmware = (support.REPO / "firmware" / "src" / "main.cpp").read_text(encoding="utf-8")
        self.assertEqual("30", re.search(r"COMMAND_STALENESS_WINDOW_SECONDS = (\d+)L", firmware).group(1), "read-only look at the committed firmware")
        backend = (support.REPO / "backend" / "src" / "main" / "java" / "com" / "nexus" / "action" / "outbox" / "CommandDeliveryLimits.java").read_text(encoding="utf-8")
        self.assertEqual("30", re.search(r"FIRMWARE_FRESHNESS_WINDOW = Duration\.ofSeconds\((\d+)\)", backend).group(1))
        versions = json.loads((support.CONTRACTS / "versions.json").read_text(encoding="utf-8"))
        self.assertEqual(30, versions["profiles"]["esp32-legacy-v1"]["commandFreshnessWindowSeconds"])


if __name__ == "__main__":
    unittest.main()
