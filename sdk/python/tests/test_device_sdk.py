"""The device SDK (ledger P5-088, P5-089, P5-091 and the shared freshness rule of P5-083/P5-084): contractVersion and a stable messageId, local
validation with the validator's fixed codes, the duplicate-redelivery hook, the 30-second absolute freshness rule against the simulator and the
ESP32 profile, and the transports."""

from __future__ import annotations

import json
import sys
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace

from tests import support
from nexus_contract.validator import validate
from nexus_device_sdk import (CommandProcessor, ContractViolation, DeviceClient, HttpTransport, InMemoryTransport, MqttTransport, TransportError, is_fresh)

DEVICE = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13"
NOW = 1_767_225_600
COMMAND_ID = "a1b2c3d4-e5f6-4789-8abc-def012345678"


def fixed_ids():
    counter = iter(range(1, 10_000))
    return lambda: uuid.UUID(int=next(counter))


def client(transport=None, now=NOW, **kwargs):
    return DeviceClient(DEVICE, transport if transport is not None else InMemoryTransport(), clock=lambda: now, new_id=fixed_ids(), **kwargs)


def command(timestamp=NOW, **overrides):
    body = {"commandId": COMMAND_ID, "capability": "LED", "command": "SET_STATE", "value": True, "timestamp": timestamp}
    body.update(overrides)
    return json.dumps(body).encode("utf-8")


class Telemetry(unittest.TestCase):
    def test_p5_088_every_telemetry_message_carries_contract_version_and_a_uuid_message_id(self):
        transport = InMemoryTransport()
        c = client(transport)
        first = c.publish_telemetry("TEMPERATURE_SENSOR", 24.5, "CELSIUS")
        second = c.publish_telemetry("TEMPERATURE_SENSOR", 24.5, "CELSIUS")
        bodies = [json.loads(sent.payload) for sent in transport.sent]
        for body in bodies:
            self.assertEqual("1.0", body["contractVersion"])
            self.assertEqual(str(uuid.UUID(body["messageId"])), body["messageId"])
            self.assertEqual("2026-01-01T00:00:00Z", body["timestamp"], "seconds precision with a Z suffix, like the firmware")
            self.assertEqual(DEVICE, body["deviceId"])
        self.assertNotEqual(first.message_id, second.message_id, "each sample has its own identity")
        self.assertEqual(first.message_id, bodies[0]["messageId"])
        self.assertEqual(("telemetry", f"nexus/devices/{DEVICE}/telemetry"), (transport.sent[0].kind, transport.sent[0].topic))

    def test_p5_088_a_replayed_message_keeps_the_same_message_id_and_the_same_bytes(self):
        transport = InMemoryTransport()
        c = client(transport)
        sample = c.publish_telemetry("TEMPERATURE_SENSOR", 21.5, "CELSIUS")
        c.redeliver(sample)
        c.redeliver_last()
        self.assertEqual(3, len(transport.sent))
        self.assertEqual({sample.payload}, {sent.payload for sent in transport.sent}, "identical bytes: an identical redelivery")
        self.assertEqual(1, len({json.loads(sent.payload)["messageId"] for sent in transport.sent}))

    def test_p5_091_the_redelivery_hook_before_anything_was_sent_is_refused(self):
        with self.assertRaises(ValueError):
            client().redeliver_last()

    def test_generic_shape_and_readback(self):
        transport = InMemoryTransport()
        c = client(transport)
        c.publish_telemetry("MOTION_SENSOR", True, "BOOLEAN", capability_key="motion", readback_command_id=COMMAND_ID)
        body = json.loads(transport.sent[0].payload)
        self.assertEqual("motion", body["capabilityKey"])
        self.assertEqual(COMMAND_ID, body["readbackCommandId"])

    def test_status_and_acknowledgement_messages(self):
        transport = InMemoryTransport()
        c = client(transport)
        c.publish_status(True)
        c.publish_status(False)
        c.publish_acknowledgement(COMMAND_ID, "REJECTED", "STALE")
        self.assertEqual([b"online", b"offline"], [sent.payload for sent in transport.sent[:2]])
        self.assertTrue(all(sent.retain for sent in transport.sent[:2]), "birth and last-will texts are retained")
        ack = json.loads(transport.sent[2].payload)
        self.assertEqual(("1.0", COMMAND_ID, "REJECTED", "STALE", NOW), (ack["contractVersion"], ack["commandId"], ack["status"], ack["reason"], ack["timestamp"]))
        self.assertEqual(f"nexus/devices/{DEVICE}/commands/ack", transport.sent[2].topic)


class LocalValidation(unittest.TestCase):
    def test_p5_089_an_invalid_message_is_refused_locally_with_the_validators_fixed_code_and_nothing_is_sent(self):
        transport = InMemoryTransport()
        c = client(transport)
        cases = [
            ("legacy value must be numeric", lambda: c.publish_telemetry("TEMPERATURE_SENSOR", "warm", "CELSIUS"), "INVALID_LEGACY_PAYLOAD"),
            ("missing unit", lambda: c.publish_telemetry("TEMPERATURE_SENSOR", 1, ""), "INVALID_LEGACY_PAYLOAD"),
            ("bad message id", lambda: c.publish_telemetry("TEMPERATURE_SENSOR", 1, "C", message_id="has space"), "INVALID_MESSAGE_ID"),
            ("bad readback id", lambda: c.publish_telemetry("TEMPERATURE_SENSOR", 1, "C", readback_command_id="x"), "MALFORMED_JSON"),
            ("unknown ack status", lambda: c.publish_acknowledgement(COMMAND_ID, "MAYBE"), "INVALID_FIELDS"),
            ("non-uuid command id", lambda: c.publish_acknowledgement("cmd-1", "ACCEPTED"), "INVALID_FIELDS"),
        ]
        for name, action, code in cases:
            with self.subTest(name):
                with self.assertRaises(ContractViolation) as caught:
                    action()
                self.assertEqual(code, caught.exception.code)
                self.assertNotIn("warm", str(caught.exception), "no payload text in the error")
        self.assertEqual([], transport.sent, "nothing reached the transport")

    def test_the_sdk_code_is_exactly_the_cli_code_for_the_same_bytes(self):
        c = client()
        message = c.build_telemetry("TEMPERATURE_SENSOR", "warm", "CELSIUS")
        with self.assertRaises(ContractViolation) as caught:
            c.check(message)
        cli = support.run_cli("validate", "--kind", "telemetry", "-", "--topic", message.topic, stdin=message.payload)
        self.assertEqual(1, cli.returncode)
        self.assertIn(caught.exception.code, cli.stdout.decode())
        self.assertEqual(caught.exception.code, validate("telemetry", message.payload, topic_device_id=DEVICE).code)

    def test_validation_can_be_switched_off_for_negative_testing_of_a_backend(self):
        transport = InMemoryTransport()
        c = client(transport, validate_before_send=False)
        c.publish_telemetry("TEMPERATURE_SENSOR", "warm", "CELSIUS")
        self.assertEqual(1, len(transport.sent), "the opt-out exists so a test can send a bad message deliberately")

    def test_a_device_id_that_is_not_a_uuid_is_refused_at_construction(self):
        with self.assertRaises(ValueError):
            DeviceClient("not-a-uuid", InMemoryTransport())


class Freshness(unittest.TestCase):
    def test_the_rule_is_absolute_and_thirty_seconds(self):
        for offset in (-30, -29, 0, 29, 30):
            self.assertTrue(is_fresh(NOW, NOW + offset), offset)
        for offset in (-31, 31, -300, 300):
            self.assertFalse(is_fresh(NOW, NOW + offset), offset)
        self.assertTrue(is_fresh(NOW + 30.9, NOW), "the device clock is whole seconds like the firmware's")
        self.assertFalse(is_fresh(NOW + 31.0, NOW))

    def test_the_processor_pipeline_and_its_acknowledgements(self):
        applied = []
        processor = CommandProcessor({"LED": applied.append}, clock=lambda: NOW)
        self.assertEqual(("ACCEPTED", None), processor.handle(command())[1:])
        self.assertEqual([True], applied)
        self.assertEqual(("ACCEPTED", None), processor.handle(command())[1:], "a repeated commandId is answered again")
        self.assertEqual([True], applied, "and applied once")
        for name, payload, expected in [
                ("stale", command(NOW - 31, commandId="a1b2c3d4-e5f6-4789-8abc-def012345601"), ("REJECTED", "STALE")),
                ("future stale", command(NOW + 31, commandId="a1b2c3d4-e5f6-4789-8abc-def012345602"), ("REJECTED", "STALE")),
                ("unsupported capability", command(commandId="a1b2c3d4-e5f6-4789-8abc-def012345603", capability="FAN"), ("REJECTED", "UNSUPPORTED")),
                ("unsupported command", command(commandId="a1b2c3d4-e5f6-4789-8abc-def012345604", command="TOGGLE"), ("REJECTED", "UNSUPPORTED")),
                ("float timestamp", command(NOW + 0.5, commandId="a1b2c3d4-e5f6-4789-8abc-def012345605"), ("REJECTED", "MALFORMED")),
                ("missing value", json.dumps({"commandId": "a1b2c3d4-e5f6-4789-8abc-def012345606", "capability": "LED", "command": "SET_STATE", "timestamp": NOW}).encode(), ("REJECTED", "MALFORMED"))]:
            with self.subTest(name):
                self.assertEqual(expected, processor.handle(payload)[1:])
        for name, payload in [("not json", b"{"), ("not an object", b"[]"), ("no command id", b'{"capability":"LED"}'), ("numeric id", b'{"commandId":5}'), ("non-uuid id", command(commandId="cmd-1"))]:
            with self.subTest(name):
                self.assertFalse(processor.handle(payload).acknowledges, "nothing to correlate an acknowledgement with")

    def test_a_handler_that_refuses_the_value_gives_malformed(self):
        def refuse(value):
            if not isinstance(value, bool):
                raise ValueError("not a boolean")
        processor = CommandProcessor({"LED": refuse}, clock=lambda: NOW)
        self.assertEqual(("REJECTED", "MALFORMED"), processor.handle(command(value="yes"))[1:])

    def test_respond_sends_the_acknowledgement_through_the_client(self):
        transport = InMemoryTransport()
        c = client(transport)
        processor = CommandProcessor({"LED": lambda v: None}, clock=lambda: NOW)
        processor.respond(command(NOW - 60), c)
        ack = json.loads(transport.sent[0].payload)
        self.assertEqual(("REJECTED", "STALE", COMMAND_ID), (ack["status"], ack["reason"], ack["commandId"]))
        processor.respond(b"{", c)
        self.assertEqual(1, len(transport.sent), "an unusable command is not acknowledged")

    def test_the_sdk_the_esp32_profile_and_the_simulator_agree_on_every_boundary(self):
        sys.path.insert(0, str(support.REPO / "simulator" / "src"))
        try:
            from nexus_simulator.actuators import LedActuator
            from nexus_simulator.command_handler import CommandHandler
        finally:
            sys.path.pop(0)
        for offset in (-300, -32, -31, -30, -29, -1, 0, 1, 29, 30, 31, 32, 300):
            payload = command(NOW + offset)
            sdk = CommandProcessor({"LED": lambda v: None}, clock=lambda: NOW).handle(payload)[1:]
            profile = validate("command", payload, profile="esp32-legacy-v1", reference_time=NOW)
            device = SimpleNamespace(write_capabilities={"LED": SimpleNamespace(actuator=LedActuator())})
            sim = CommandHandler(device, clock=lambda: NOW).handle(payload)[1:]
            expected_fresh = abs(offset) <= 30
            with self.subTest(offset=offset):
                self.assertEqual(expected_fresh, sdk == ("ACCEPTED", None))
                self.assertEqual(expected_fresh, profile.verdict == "ACCEPT")
                self.assertEqual(sdk, sim, "the simulator and the SDK give the same status and reason")


class Transports(unittest.TestCase):
    def test_the_mqtt_transport_publishes_with_qos_one_and_keeps_the_retain_flag(self):
        calls = []

        class FakePaho:
            def publish(self, topic, payload, qos=0, retain=False):
                calls.append((topic, payload, qos, retain))

        c = client(MqttTransport(FakePaho()))
        c.publish_status(True)
        c.publish_telemetry("TEMPERATURE_SENSOR", 1, "C")
        self.assertEqual([(f"nexus/devices/{DEVICE}/status", b"online", 1, True)], calls[:1])
        self.assertEqual((1, False), (calls[1][2], calls[1][3]))

    def test_the_http_transport_posts_to_the_adapter_with_the_bearer_key_only_in_the_header(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen.append((self.path, self.headers.get("Authorization"), self.headers.get("Content-Type"), self.rfile.read(int(self.headers["Content-Length"]))))
                status, body = (401, b'{"error":"UNAUTHENTICATED"}') if self.headers.get("Authorization") == "Bearer wrong" else (200, b'{"result":"ACCEPTED"}')
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            origin = f"http://127.0.0.1:{server.server_port}"
            key = "nxk1_" + "a" * 24 + "_" + "B" * 43
            c = client(HttpTransport(origin, DEVICE, key))
            c.publish_telemetry("TEMPERATURE_SENSOR", 20.5, "CELSIUS")
            c.publish_status(True)
            c.publish_acknowledgement(COMMAND_ID, "ACCEPTED")
            self.assertEqual([f"/api/v1/device-adapter/devices/{DEVICE}/{p}" for p in ("telemetry", "status", "acknowledgements")], [s[0] for s in seen])
            self.assertTrue(all(s[1] == f"Bearer {key}" for s in seen))
            self.assertEqual(["application/json", "text/plain; charset=utf-8", "application/json"], [s[2] for s in seen])
            self.assertEqual(b"online", seen[1][3])
            bad = client(HttpTransport(origin, DEVICE, "wrong"))
            with self.assertRaises(TransportError) as caught:
                bad.publish_telemetry("TEMPERATURE_SENSOR", 1, "C")
            self.assertEqual((401, "UNAUTHENTICATED"), (caught.exception.status, caught.exception.code))
            self.assertNotIn("wrong", str(caught.exception))
            self.assertNotIn(key, repr(c._transport))
            self.assertNotIn("wrong", repr(bad._transport))
        finally:
            server.shutdown()
        with self.assertRaises(TransportError):
            client(HttpTransport("http://127.0.0.1:1", DEVICE, "k", timeout=1)).publish_status(True)

    def test_the_http_transport_refuses_an_origin_that_could_carry_the_key(self):
        for bad in ("ftp://host", "http://user:pass@host", "http://host/?key=1", "host"):
            with self.assertRaises(ValueError):
                HttpTransport(bad, DEVICE, "k")


if __name__ == "__main__":
    unittest.main()
