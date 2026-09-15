import json
import time

from nexus_simulator.command_handler import CommandHandler
from nexus_simulator.devices import SimulatedDevice


def make_device():
    return SimulatedDevice(
        device_id="dev-1",
        name="ESP32-SIM-TEST",
        serial_number="SIM-ESP32-SIM-TEST",
        capabilities=[
            {"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"},
            {"capabilityType": "SERVO", "direction": "WRITE", "unit": "DEGREES"},
            {"capabilityType": "RELAY", "direction": "WRITE", "unit": "BOOLEAN",
             "metadata": {"channels": [1, 2, 3, 4]}},
            {"capabilityType": "TEMPERATURE", "direction": "READ", "unit": "CELSIUS"},
        ],
    )


def command_payload(**overrides):
    payload = {
        "commandId": "cmd-1",
        "capability": "LED",
        "command": "SET_STATE",
        "value": True,
        "timestamp": int(time.time()),
    }
    payload.update(overrides)
    return json.dumps(payload).encode("utf-8")


def test_valid_led_command_is_accepted_and_mutates_state():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(command_payload())

    assert command_id == "cmd-1"
    assert status == "ACCEPTED"
    assert reason is None
    assert device.write_capabilities["LED"].actuator.state is True


def test_relay_command_applies_channel_state():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(command_payload(
        capability="RELAY", value={"channel": 2, "state": True}
    ))

    assert status == "ACCEPTED"
    assert device.write_capabilities["RELAY"].actuator.channels[2] is True
    assert device.write_capabilities["RELAY"].actuator.channels[1] is False


def test_malformed_json_is_rejected_with_no_command_id():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(b"not json")

    assert command_id is None
    assert status == "REJECTED"
    assert reason == "MALFORMED"


def test_missing_required_field_is_rejected_malformed():
    device = make_device()
    handler = CommandHandler(device)

    payload = json.dumps({"commandId": "cmd-2", "capability": "LED"}).encode("utf-8")
    command_id, status, reason = handler.handle(payload)

    assert command_id == "cmd-2"
    assert status == "REJECTED"
    assert reason == "MALFORMED"


def test_unknown_capability_is_rejected_unsupported():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(command_payload(capability="BUZZER"))

    assert status == "REJECTED"
    assert reason == "UNSUPPORTED"


def test_read_capability_receiving_write_command_is_rejected_unsupported():
    device = make_device()
    handler = CommandHandler(device)

    # TEMPERATURE is provisioned READ-only - not in write_capabilities at all.
    command_id, status, reason = handler.handle(command_payload(capability="TEMPERATURE"))

    assert status == "REJECTED"
    assert reason == "UNSUPPORTED"


def test_unsupported_command_verb_is_rejected():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(command_payload(command="TOGGLE"))

    assert status == "REJECTED"
    assert reason == "UNSUPPORTED"


def test_invalid_servo_value_is_rejected_malformed():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(command_payload(capability="SERVO", value=270))

    assert status == "REJECTED"
    assert reason == "MALFORMED"


def test_invalid_relay_channel_is_rejected_malformed():
    device = make_device()
    handler = CommandHandler(device)

    command_id, status, reason = handler.handle(command_payload(
        capability="RELAY", value={"channel": 9, "state": True}
    ))

    assert status == "REJECTED"
    assert reason == "MALFORMED"


def test_stale_command_is_rejected():
    device = make_device()
    handler = CommandHandler(device, stale_window_seconds=60, clock=lambda: 10_000.0)

    command_id, status, reason = handler.handle(command_payload(timestamp=1000))

    assert status == "REJECTED"
    assert reason == "STALE"


def test_duplicate_command_id_does_not_mutate_state_twice_but_reacks_consistently():
    device = make_device()
    handler = CommandHandler(device)

    first = handler.handle(command_payload(value=True))
    # simulate a later, unrelated command flipping it
    device.write_capabilities["LED"].actuator.state = False
    second = handler.handle(command_payload(value=True))  # same commandId replayed

    assert first == second
    assert second[1] == "ACCEPTED"
    # The duplicate replay must not re-apply the command - state stays
    # whatever the later unrelated mutation set it to.
    assert device.write_capabilities["LED"].actuator.state is False


def test_duplicate_of_a_rejected_command_re_acks_the_same_rejection():
    device = make_device()
    handler = CommandHandler(device)

    first = handler.handle(command_payload(capability="SERVO", value=999))
    second = handler.handle(command_payload(capability="SERVO", value=999))

    assert first == second
    assert second[1] == "REJECTED"
    assert second[2] == "MALFORMED"
