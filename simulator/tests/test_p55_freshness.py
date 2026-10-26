"""P5.5 (decision D4): the simulator applies the firmware's command freshness rule, with an injected clock (ledger P5-083, P5-084, P5-086, P5-087)."""
import json
import logging

import pytest

from nexus_simulator import command_handler as command_handler_module
from nexus_simulator.command_handler import CommandHandler, DEFAULT_STALE_WINDOW_SECONDS
from nexus_simulator.config import SimulatorConfig
from nexus_simulator.devices import SimulatedDevice

NOW = 1_767_225_600  # 2026-01-01T00:00:00Z


def make_device(**kwargs):
    return SimulatedDevice(device_id="dev-1", name="D", serial_number="SN",
                           capabilities=[{"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"}], **kwargs)


def command(command_id="c-1", timestamp=NOW, **overrides):
    payload = {"commandId": command_id, "capability": "LED", "command": "SET_STATE", "value": True, "timestamp": timestamp}
    payload.update(overrides)
    return json.dumps(payload).encode("utf-8")


def handler(window=None, now=NOW):
    kwargs = {} if window is None else {"stale_window_seconds": window}
    return CommandHandler(make_device(), clock=lambda: now, **kwargs)


@pytest.mark.parametrize("age", [0, 1, 29, 30])
def test_p5_083_a_command_up_to_thirty_seconds_old_is_accepted(age):
    assert handler().handle(command(timestamp=NOW - age))[1:] == ("ACCEPTED", None)


@pytest.mark.parametrize("age", [31, 32, 60, 300, 301, 100_000])
def test_p5_083_a_command_older_than_thirty_seconds_is_rejected_stale(age):
    assert handler().handle(command(timestamp=NOW - age))[1:] == ("REJECTED", "STALE")


@pytest.mark.parametrize("ahead", [1, 29, 30])
def test_p5_084_a_command_dated_up_to_thirty_seconds_in_the_future_is_accepted_like_the_firmware(ahead):
    assert handler().handle(command(timestamp=NOW + ahead))[1:] == ("ACCEPTED", None)


@pytest.mark.parametrize("ahead", [31, 60, 100_000])
def test_p5_084_a_command_dated_more_than_thirty_seconds_in_the_future_is_stale_exactly_as_the_firmware_computes(ahead):
    assert handler().handle(command(timestamp=NOW + ahead))[1:] == ("REJECTED", "STALE")


def test_the_device_clock_is_whole_seconds_like_the_firmwares_time_function():
    # 30.9 s after the command: the firmware's integer clock reads 30, so the command is still fresh; one second later it is not
    assert handler(now=NOW + 30.9).handle(command())[1] == "ACCEPTED"
    assert handler(now=NOW + 31.0).handle(command())[1:] == ("REJECTED", "STALE")


def test_p5_086_the_defaults_are_thirty_seconds_in_the_handler_and_in_the_configuration():
    assert DEFAULT_STALE_WINDOW_SECONDS == 30.0
    assert CommandHandler(make_device()).stale_window_seconds == 30.0
    assert SimulatorConfig().command_stale_window_seconds == 30.0
    assert make_device().command_handler.stale_window_seconds == 30.0
    assert make_device(command_stale_window_seconds=45).command_handler.stale_window_seconds == 45


def test_p5_086_a_deliberately_larger_window_must_be_explicit_and_is_logged(caplog):
    with caplog.at_level(logging.WARNING, logger=command_handler_module.__name__):
        CommandHandler(make_device())
        CommandHandler(make_device(), stale_window_seconds=30)
        CommandHandler(make_device(), stale_window_seconds=10)
    assert not caplog.records, "the default and any smaller window are silent"
    with caplog.at_level(logging.WARNING, logger=command_handler_module.__name__):
        CommandHandler(make_device(), stale_window_seconds=300)
    assert len(caplog.records) == 1
    assert "300" in caplog.records[0].getMessage() and "firmware" in caplog.records[0].getMessage()
    # and an explicitly larger window really is applied
    assert handler(window=300).handle(command(timestamp=NOW - 120))[1] == "ACCEPTED"


@pytest.mark.parametrize("timestamp", [NOW + 0.5, str(NOW), True, None, [NOW], {"a": 1}])
def test_the_timestamp_must_be_a_json_integer_like_the_firmware_requires(timestamp):
    status = handler().handle(command(timestamp=timestamp))[1:]
    assert status in (("REJECTED", "MALFORMED"),), timestamp


def test_p5_087_the_stale_decision_is_made_before_the_actuator_and_does_not_mutate_state():
    h = handler()
    device_state_before = h.device.write_capabilities["LED"].actuator.state
    assert h.handle(command(timestamp=NOW - 100))[1:] == ("REJECTED", "STALE")
    assert h.device.write_capabilities["LED"].actuator.state == device_state_before, "a stale command never reaches the actuator"
