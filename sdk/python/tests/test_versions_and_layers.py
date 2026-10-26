"""Version resolution (P5-015..P5-018, P5-020), status text (P5-009), layering and NOT_PERFORMED (P5-155),
the ESP32 profile (P5-154) and the generic command contract (P5-007, P5-153)."""

from __future__ import annotations

import json
import time
import unittest
from unittest import mock

from tests import support  # noqa: F401  (puts the package on sys.path)
from nexus_contract import loader, versions
from nexus_contract.validator import FAIL, NOT_APPLICABLE, NOT_PERFORMED, PASS, validate

D = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13"
C = "a1b2c3d4-e5f6-4789-8abc-def012345678"
T = 1767225600


def telemetry(**over):
    body = {"deviceId": D, "timestamp": "2026-01-01T00:00:00Z", "sensorType": "TEMPERATURE_SENSOR", "value": 24.5, "unit": "CELSIUS"}
    body.update(over)
    return json.dumps(body).encode()


def command(**over):
    body = {"commandId": C, "capability": "LED", "command": "SET_STATE", "value": True, "timestamp": T}
    body.update(over)
    return json.dumps(body).encode()


def ack(**over):
    body = {"commandId": C, "status": "ACCEPTED", "timestamp": T}
    body.update(over)
    return json.dumps(body).encode()


class VersionResolution(unittest.TestCase):
    def test_p5_015_unversioned_resolves_to_legacy_v1(self):
        result = validate("telemetry", telemetry())
        self.assertEqual("legacy-v1", result.version)
        self.assertEqual("ACCEPT", result.verdict)

    def test_p5_016_resolution_is_a_total_function(self):
        table = [
            (dict(), "legacy-v1", None),
            (dict(body_value="", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=None, present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=1, present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=1.0, present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=True, present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=["1.0"], present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=".1", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.0.0", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="v1.0", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="01.0", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.00", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="-1.0", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.-1", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value=" 1.0", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.0 ", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.0\n", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
            (dict(body_value="1.0", present=True), "exact", None),
            (dict(body_value="1.1", present=True), "forward-minor", None),
            (dict(body_value="1.999", present=True), "forward-minor", None),
            (dict(body_value="2.0", present=True), "rejected", "UNSUPPORTED_CONTRACT_MAJOR"),
            (dict(body_value="0.9", present=True), "rejected", "UNSUPPORTED_CONTRACT_MAJOR"),
            (dict(body_value="10.0", present=True), "rejected", "UNSUPPORTED_CONTRACT_MAJOR"),
            (dict(body_value="1" * 12 + ".0", present=True), "rejected", "INVALID_CONTRACT_VERSION"),
        ]
        for kwargs, kind, code in table:
            with self.subTest(kwargs=kwargs):
                resolution = versions.resolve(**kwargs)
                self.assertEqual((kind, code), (resolution.kind, resolution.code))

    def test_p5_017_unsupported_major_is_rejected_never_reinterpreted(self):
        for version in ("2.0", "0.9", "3.4"):
            with self.subTest(version=version):
                result = validate("telemetry", json.dumps({**json.loads(telemetry()), "contractVersion": version}).encode())
                self.assertEqual(("REJECT", "UNSUPPORTED_CONTRACT_MAJOR"), (result.verdict, result.code))
                self.assertEqual("unresolved", result.version)

    def test_p5_018_higher_minor_is_validated_strictly_on_known_fields(self):
        body = {**json.loads(telemetry()), "contractVersion": "1.5", "futureField": {"a": 1}}
        ok = validate("telemetry", json.dumps(body).encode())
        self.assertEqual("ACCEPT", ok.verdict)
        self.assertTrue(ok.forward_minor)
        self.assertEqual("1.0", ok.version)
        self.assertEqual(1, ok.unknown_fields)
        for field, bad in (("deviceId", "nope"), ("timestamp", 5), ("messageId", "a b"), ("value", None)):
            with self.subTest(field=field):
                broken = validate("telemetry", json.dumps({**body, field: bad}).encode())
                self.assertEqual("REJECT", broken.verdict, "a newer minor never loosens a known field")

    def test_p5_020_support_window_lists_only_actual_versions(self):
        table = loader.versions()
        self.assertEqual([1], table["supportedMajors"])
        self.assertEqual({"1": [0]}, table["supportedMinors"])
        self.assertEqual("legacy-v1", table["legacy"])
        for major in (0, 1, 2, 3):
            for minor in range(0, 4):
                resolution = versions.resolve(f"{major}.{minor}", present=True)
                accepted = resolution.kind in ("exact", "forward-minor")
                self.assertEqual(major in table["supportedMajors"], accepted, f"{major}.{minor}")

    def test_header_and_body_conflict_is_rejected(self):
        self.assertEqual("CONTRACT_VERSION_CONFLICT", versions.resolve("1.0", present=True, header="1.1").code)
        self.assertEqual("exact", versions.resolve("1.0", present=True, header="1.0").kind)
        self.assertEqual("exact", versions.resolve(None, present=False, header="1.0").kind)
        result = validate("telemetry", json.dumps({**json.loads(telemetry()), "contractVersion": "1.0"}).encode(), declared_version="1.1")
        self.assertEqual("CONTRACT_VERSION_CONFLICT", result.code)


class RequiredFeatures(unittest.TestCase):
    def test_p5_144_non_empty_requires_is_rejected_and_unknown_fields_only_counted(self):
        body = {**json.loads(telemetry()), "contractVersion": "1.2", "requires": ["frobnicate"]}
        result = validate("telemetry", json.dumps(body).encode())
        self.assertEqual(("REJECT", "UNSUPPORTED_REQUIRED_FEATURE"), (result.verdict, result.code))
        empty = validate("telemetry", json.dumps({**body, "requires": []}).encode())
        self.assertEqual("ACCEPT", empty.verdict)
        for bad in ("x", ["A"], ["a"] * 9, [1], ["x" * 33]):
            with self.subTest(requires=bad):
                self.assertEqual("INVALID_FIELDS", validate("telemetry", json.dumps({**body, "requires": bad}).encode()).code)
        self.assertEqual(0, validate("telemetry", telemetry()).unknown_fields)
        self.assertEqual(2, validate("telemetry", telemetry(a=1, b=[2])).unknown_fields)

    def test_legacy_flag_treats_version_members_as_unknown_fields(self):
        body = {**json.loads(telemetry()), "contractVersion": "9.9", "requires": ["frobnicate"]}
        result = validate("telemetry", json.dumps(body).encode(), legacy=True)
        self.assertEqual("ACCEPT", result.verdict)
        self.assertEqual("legacy-v1", result.version)
        self.assertEqual(2, result.unknown_fields)


class StatusText(unittest.TestCase):
    def test_p5_009_status_is_plain_text_trimmed_and_case_sensitive(self):
        accepted = [b"online", b"offline", b" online\n", b"\toffline\r\n", b"\x01online", b"online\x00", b"\x00online "]
        rejected = [b"ONLINE", b"Online", b"OFFLINE", b"", b"   ", b"onlin", b"online online", b'"online"', b'{"status":"online"}',
                    "online ".encode(), "﻿online".encode(), "online​".encode(), b"\xff\xfeonline", b"on line"]
        for payload in accepted:
            with self.subTest(payload=payload):
                self.assertEqual("ACCEPT", validate("status", payload).verdict)
        for payload in rejected:
            with self.subTest(payload=payload):
                self.assertEqual("REJECT", validate("status", payload).verdict)

    def test_status_has_no_envelope_and_no_body_version(self):
        result = validate("status", b"online")
        self.assertEqual(NOT_APPLICABLE, result.layer("identity").status)
        self.assertEqual("ACCEPT", validate("status", b"online", declared_version="1.0").verdict)
        self.assertEqual("CONTRACT_VERSION_CONFLICT" if False else "UNSUPPORTED_CONTRACT_MAJOR",
                         validate("status", b"online", declared_version="2.0").code)

    def test_status_schema_is_a_plain_string_schema(self):
        schema = loader.schema(loader.versions()["kinds"]["status"])
        self.assertEqual("string", schema["type"])
        self.assertEqual(["online", "offline"], schema["enum"])


class GenericCommandAndProfile(unittest.TestCase):
    def test_p5_007_p5_153_generic_command_covers_every_backend_value_shape(self):
        for capability, value in (("LED", True), ("FAN", False), ("BUZZER", True), ("SERVO", 0), ("SERVO", 90), ("SERVO", 180),
                                  ("RELAY", {"channel": 1, "state": True}), ("MODE", "AUTO")):
            with self.subTest(capability=capability, value=value):
                self.assertEqual("ACCEPT", validate("command", command(capability=capability, value=value)).verdict)

    def test_p5_007_command_structure_is_pinned(self):
        for name, payload in (("float timestamp", command(timestamp=1767225600.5)), ("iso timestamp", command(timestamp="2026-01-01T00:00:00Z")),
                              ("string timestamp", command(timestamp="1767225600")), ("null value", command(value=None)),
                              ("array value", command(value=[True])), ("missing commandId", json.dumps({"capability": "LED", "command": "SET_STATE", "value": True, "timestamp": T}).encode()),
                              ("non-uuid commandId", command(commandId="abc")), ("empty capability", command(capability="")),
                              ("numeric capability", command(capability=5)), ("empty command", command(command=""))):
            with self.subTest(name):
                self.assertEqual("REJECT", validate("command", payload).verdict)

    def test_p5_154_esp32_profile_mirrors_the_firmware(self):
        profile = "esp32-legacy-v1"
        ok = validate("command", command(), profile=profile, reference_time=T)
        self.assertEqual("ACCEPT", ok.verdict)
        self.assertEqual(PASS, ok.layer("profile").status)
        cases = [
            ("servo integer", command(capability="SERVO", value=90), "MALFORMED"),
            ("relay object", command(capability="RELAY", value={"channel": 1, "state": True}), "MALFORMED"),
            ("string boolean", command(value="true"), "MALFORMED"),
            ("fan boolean", command(capability="FAN"), "UNSUPPORTED"),
            ("other command", command(command="TOGGLE"), "UNSUPPORTED"),
            ("timestamp beyond a 32-bit long", command(timestamp=2147483648), "MALFORMED"),
            ("negative beyond a 32-bit long", command(timestamp=-2147483649), "MALFORMED"),
        ]
        for name, payload, code in cases:
            with self.subTest(name):
                generic = validate("command", payload)
                result = validate("command", payload, profile=profile, reference_time=T)
                self.assertEqual("ACCEPT", generic.verdict, "valid under the generic contract")
                self.assertEqual(("REJECT", code), (result.verdict, result.code))
        self.assertEqual("ACCEPT", validate("command", command(timestamp=2147483647), profile=profile).verdict)

    def test_p5_154_freshness_uses_the_absolute_30_second_window(self):
        fixture = json.loads((support.FIXTURES / "manifest.json").read_text())["freshnessCases"]
        for offset in fixture["offsetsSeconds"]:
            with self.subTest(offset=offset):
                result = validate("command", command(), profile="esp32-legacy-v1", reference_time=T + offset)
                stale = abs(offset) > 30
                self.assertEqual("STALE" if stale else None, result.code)
                self.assertEqual(FAIL if stale else PASS, result.layer("freshness").status)
        self.assertEqual([-31, 31], fixture["expectStale"])

    def test_p5_155_layers_are_reported_separately_and_missing_context_is_not_a_pass(self):
        bare = validate("command", command())
        self.assertEqual(NOT_PERFORMED, bare.layer("freshness").status)
        self.assertEqual(NOT_PERFORMED, bare.layer("capability").status)
        self.assertEqual(NOT_APPLICABLE, bare.layer("profile").status)
        self.assertEqual(NOT_APPLICABLE, bare.layer("identity").status)
        self.assertEqual(["freshness", "capability"], bare.not_performed)
        self.assertEqual("ACCEPT", bare.verdict)
        no_topic = validate("telemetry", telemetry())
        self.assertEqual(NOT_PERFORMED, no_topic.layer("identity").status)
        self.assertEqual(PASS, validate("telemetry", telemetry(), topic_device_id=D).layer("identity").status)
        mismatch = validate("telemetry", telemetry(), topic_device_id="9b1d3e57-2c44-4a0f-a6d8-71e5c0b3f492")
        self.assertEqual(("REJECT", "TOPIC_DEVICE_MISMATCH"), (mismatch.verdict, mismatch.code))
        no_window = validate("command", command(), reference_time=T)
        self.assertEqual(NOT_PERFORMED, no_window.layer("freshness").status, "a generic command has no window unless one is given")
        windowed = validate("command", command(timestamp=T - 5), reference_time=T, freshness_window=3)
        self.assertEqual(("REJECT", "STALE"), (windowed.verdict, windowed.code))

    def test_p5_155_failed_earlier_layer_leaves_later_layers_not_performed(self):
        broken = validate("command", command(commandId="x"), profile="esp32-legacy-v1", reference_time=T)
        self.assertEqual(FAIL, broken.layer("structural").status)
        self.assertEqual(NOT_PERFORMED, broken.layer("freshness").status)

    def test_p5_155_the_wall_clock_is_never_read(self):
        with mock.patch.object(time, "time", side_effect=AssertionError("wall clock read")), \
                mock.patch.object(time, "monotonic", side_effect=AssertionError("wall clock read")):
            self.assertEqual("ACCEPT", validate("command", command(), profile="esp32-legacy-v1", reference_time=T).verdict)
            self.assertEqual("ACCEPT", validate("command", command()).verdict)

    def test_capability_layer_mirrors_command_value_validator(self):
        cases = [
            ({"type": "LED"}, command(value=True), PASS), ({"type": "LED"}, command(value="true"), FAIL),
            ({"type": "SERVO"}, command(capability="SERVO", value=180), PASS), ({"type": "SERVO"}, command(capability="SERVO", value=181), FAIL),
            ({"type": "SERVO"}, command(capability="SERVO", value=True), FAIL),
            ({"type": "RELAY", "channels": [1, 2]}, command(capability="RELAY", value={"channel": 2, "state": False}), PASS),
            ({"type": "RELAY", "channels": [1, 2]}, command(capability="RELAY", value={"channel": 3, "state": False}), FAIL),
            ({"type": "RELAY"}, command(capability="RELAY", value={"channel": 2, "state": False}), NOT_PERFORMED),
            ({"type": "RELAY"}, command(capability="RELAY", value={"channel": 2, "state": "x"}), FAIL),
            ({"type": "LED"}, command(command="set_state"), FAIL), ({"type": "MOTION"}, command(), NOT_PERFORMED),
        ]
        for capability_profile, payload, status in cases:
            with self.subTest(capability_profile=capability_profile, payload=payload):
                self.assertEqual(status, validate("command", payload, capability_profile=capability_profile).layer("capability").status)


class TelemetryShapes(unittest.TestCase):
    def test_p5_005_legacy_and_generic_shapes(self):
        self.assertEqual("ACCEPT", validate("telemetry", telemetry()).verdict)
        generic = json.dumps({"deviceId": D, "timestamp": "2026-01-01T00:00:00Z", "capabilityKey": "motion", "value": True}).encode()
        self.assertEqual("ACCEPT", validate("telemetry", generic).verdict)
        self.assertEqual("INVALID_LEGACY_PAYLOAD", validate("telemetry", telemetry(value="24.5")).code)
        neither = json.dumps({"deviceId": D, "timestamp": "2026-01-01T00:00:00Z", "value": 1}).encode()
        self.assertEqual("INVALID_LEGACY_PAYLOAD", validate("telemetry", neither).code)

    def test_p5_006_message_id_alphabet_and_length(self):
        for good in ("a", "A-z_0.9:Z", "x" * 64):
            self.assertEqual("ACCEPT", validate("telemetry", telemetry(messageId=good)).verdict, good)
        for bad in ("", "x" * 65, "a b", "a/b", "café", 123, True, {}, []):
            with self.subTest(bad=bad):
                self.assertEqual("INVALID_MESSAGE_ID", validate("telemetry", telemetry(messageId=bad)).code)
        self.assertEqual("ACCEPT", validate("telemetry", telemetry(messageId=None)).verdict)

    def test_legacy_timestamp_forms_remain_accepted(self):
        for stamp in ("2026-01-01T00:00:00Z", "2026-10-17T12:00:00.123456+00:00", "2026-01-01T00:00:00.123456789Z", "2026-01-01T01:00:00+01:00"):
            self.assertEqual("ACCEPT", validate("telemetry", telemetry(timestamp=stamp)).verdict, stamp)
        for stamp in ("2026-01-01", "2026-01-01T00:00:00", "2026-02-30T00:00:00Z", "yesterday", 5):
            self.assertEqual("REJECT", validate("telemetry", telemetry(timestamp=stamp)).verdict, str(stamp))

    def test_uppercase_payload_device_id_matches_the_lowercase_topic(self):
        self.assertEqual("ACCEPT", validate("telemetry", telemetry(deviceId=D.upper()), topic_device_id=D).verdict)
        self.assertEqual("TOPIC_DEVICE_MISMATCH", validate("telemetry", telemetry(), topic_device_id=D.upper()).code)

    def test_non_integral_numbers_are_not_integers(self):
        for text in ("1767225600.0", "1.7e9", "1767225600.5"):
            payload = ('{"commandId":"%s","status":"ACCEPTED","timestamp":%s}' % (C, text)).encode()
            self.assertEqual("REJECT", validate("acknowledgement", payload).verdict, text)

    def test_acknowledgement_shape(self):
        self.assertEqual("ACCEPT", validate("acknowledgement", ack()).verdict)
        self.assertEqual("ACCEPT", validate("acknowledgement", ack(status="REJECTED", reason="STALE")).verdict)
        self.assertEqual("ACCEPT", validate("acknowledgement", ack(reason=None)).verdict)
        for name, payload in (("lowercase", ack(status="accepted")), ("unknown", ack(status="PENDING")), ("numeric id", ack(commandId=5)),
                              ("float ts", ack(timestamp=1.5)), ("string ts", ack(timestamp="1")), ("no status", json.dumps({"commandId": C, "timestamp": T}).encode())):
            self.assertEqual("REJECT", validate("acknowledgement", payload).verdict, name)

    def test_malformed_documents_are_rejected_with_a_fixed_code(self):
        for payload in (b"", b"  ", b"null", b"[]", b"123", b'"x"', b'{"a":', b"{'a':1}", b'{"a":1} x', b'{"a":NaN}', b'{"a":1,"a":2}',
                        b"\xef\xbb\xbf{}", b'{"deviceId":"\xff"}'):
            with self.subTest(payload=payload):
                result = validate("telemetry", payload)
                self.assertEqual(("REJECT", "MALFORMED_JSON"), (result.verdict, result.code))


if __name__ == "__main__":
    unittest.main()
