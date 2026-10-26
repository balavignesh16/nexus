"""The simulator's telemetry carries an optional UUID messageId and can model redelivery (P4.5, R17, R25; ledger P4-117, P4-118)."""
import importlib.util
import json
import re
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from nexus_simulator.mqtt_publisher import MQTTPublisher
from nexus_simulator.publishers import HTTPPublisher

# the backend's messageId format (TelemetryRequest.MESSAGE_ID_PATTERN)
BACKEND_MESSAGE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")


def mqtt_publisher(sent):
    publisher = MQTTPublisher.__new__(MQTTPublisher)
    publisher.config = SimpleNamespace(mqtt_topic_template="nexus/devices/{device_id}/telemetry", mqtt_qos=1)
    publisher.last_publication = None
    publisher.client = SimpleNamespace(publish=lambda topic, payload, qos: (
        sent.append((topic, payload, qos)) or SimpleNamespace(wait_for_publish=lambda: None)))
    return publisher


def test_every_mqtt_publication_carries_a_fresh_valid_uuid_message_id_and_keeps_the_existing_contract():
    sent = []
    publisher = mqtt_publisher(sent)
    for _ in range(3):
        assert publisher.publish("device-1", "TEMPERATURE", 21.5, "CELSIUS")[0]
    payloads = [json.loads(p) for _, p, _ in sent]
    ids = [p["messageId"] for p in payloads]
    assert len(set(ids)) == 3, "a new id for every publication"
    for message_id in ids:
        assert str(uuid.UUID(message_id)) == message_id
        assert BACKEND_MESSAGE_ID.match(message_id)
    for payload in payloads:
        # the pre-existing contract is untouched: the same keys and values, with the id as one additive field
        assert {"deviceId", "timestamp", "sensorType", "value", "unit"} <= payload.keys()
        assert payload["deviceId"] == "device-1" and payload["sensorType"] == "TEMPERATURE"
        assert payload["value"] == 21.5 and payload["unit"] == "CELSIUS"
        assert set(payload) - {"deviceId", "timestamp", "sensorType", "value", "unit", "messageId"} == set()


def test_readback_telemetry_also_carries_an_id_and_a_caller_supplied_id_is_kept():
    sent = []
    publisher = mqtt_publisher(sent)
    publisher.publish("device-1", "LED_STATE", True, "BOOLEAN", readback_command_id="command-1", capability_key="led_state")
    publisher.publish("device-1", "TEMPERATURE", 20, "CELSIUS", message_id="fixed-id-1")
    first, second = (json.loads(p) for _, p, _ in sent)
    assert BACKEND_MESSAGE_ID.match(first["messageId"]) and first["readbackCommandId"] == "command-1"
    assert second["messageId"] == "fixed-id-1"


def test_the_redelivery_hook_resends_the_identical_bytes():
    sent = []
    publisher = mqtt_publisher(sent)
    assert publisher.republish_last() == (False, 0.0), "nothing to redeliver before the first publication"
    assert publisher.publish("device-1", "TEMPERATURE", 21.5, "CELSIUS")[0]
    assert publisher.republish_last()[0]
    assert publisher.republish_last()[0]
    assert len(sent) == 3
    assert sent[0] == sent[1] == sent[2], "same topic, same bytes (timestamp and messageId included), same QoS"


def test_a_new_publication_after_a_redelivery_gets_a_new_id():
    sent = []
    publisher = mqtt_publisher(sent)
    publisher.publish("device-1", "TEMPERATURE", 21.5, "CELSIUS")
    publisher.republish_last()
    publisher.publish("device-1", "TEMPERATURE", 21.5, "CELSIUS")
    ids = [json.loads(p)["messageId"] for _, p, _ in sent]
    assert ids[0] == ids[1] != ids[2]


def test_the_http_publisher_carries_an_id_resends_identically_and_treats_a_duplicate_200_as_success():
    publisher = HTTPPublisher(backend_url="http://localhost:8080/api/v1")
    with patch("urllib.request.urlopen") as urlopen:
        created, duplicate = MagicMock(status=201), MagicMock(status=200)
        urlopen.return_value.__enter__.side_effect = [created, duplicate]
        assert publisher.publish(device_id="123", sensor_type="TEMPERATURE", value=22.5, unit="CELSIUS")[0]
        assert publisher.republish_last()[0], "HTTP 200 (a recognised repeat) is a successful no-op, not a failure"
        first, second = (call.args[0].data for call in urlopen.call_args_list)
        assert first == second
        assert BACKEND_MESSAGE_ID.match(json.loads(first)["messageId"])


def test_the_grand_simulation_sends_boolean_capabilities_in_the_generic_boolean_shape():
    """The repository runner used to send the legacy numeric 1.0/0.0 for MOTION and BUTTON, which the backend rejects."""
    path = Path(__file__).resolve().parents[2] / "grand_simulation.py"
    spec = importlib.util.spec_from_file_location("grand_simulation_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    calls = []
    publisher = SimpleNamespace(publish=lambda *args, **kwargs: calls.append((args, kwargs)) or (True, 1.0))

    def capability(unit, values):
        return SimpleNamespace(unit=unit, generator=SimpleNamespace(generate=lambda it=iter(values): next(it)))

    device = SimpleNamespace(device_id="dev-1", mqtt_publisher=publisher, read_capabilities={
        "MOTION": capability("BOOLEAN", [True]), "BUTTON": capability(None, [False]), "GAS": capability("PPM", [66.0])})
    module.publish_tick([device])

    by_type = {args[1]: (args, kwargs) for args, kwargs in calls}
    for boolean_type, expected in (("MOTION", True), ("BUTTON", False)):
        args, kwargs = by_type[boolean_type]
        assert args[2] is expected, "a JSON boolean, never 1.0 or 0.0"
        assert args[3] == "BOOLEAN"
        assert kwargs == {"capability_key": boolean_type.lower()}
    args, kwargs = by_type["GAS"]
    assert args[2] == 66.0 and args[3] == "PPM" and kwargs == {}, "numeric capabilities are unchanged"
