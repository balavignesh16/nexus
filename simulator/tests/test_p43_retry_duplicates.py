"""Phase 4 (P4.3) ledger P4-029 and the simulator side of P4-121.

TEST-ONLY. The simulator implementation is not changed (decision A8): its 300 second default stale window and its
one-directional age check stay as they are. Evidence here is SIMULATOR evidence only; it is not firmware evidence and not a
claim about physical actuation. The committed firmware has NO commandId deduplication (see backend R26), so what is shown
below is a property of the simulator's own bounded history, not of the real device.
"""
import json

from nexus_simulator.command_handler import CommandHandler
from nexus_simulator.devices import SimulatedDevice

NOW = 10_000.0


def make_device():
    return SimulatedDevice(
        device_id="dev-1",
        name="ESP32-SIM-TEST",
        serial_number="SIM-ESP32-SIM-TEST",
        capabilities=[{"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"}],
    )


def retried_payload(command_id="11111111-2222-3333-4444-555555555555", value=True, timestamp=int(NOW)):
    """What a retry puts on the wire: the SAME bytes as the first attempt (the backend never rebuilds or re-stamps them)."""
    return json.dumps({"commandId": command_id, "capability": "LED", "command": "SET_STATE", "value": value,
                       "timestamp": timestamp}).encode("utf-8")


def test_byte_identical_retransmissions_apply_an_absolute_state_command_once_and_ack_each_receipt():
    device = make_device()
    handler = CommandHandler(device, clock=lambda: NOW)
    wire = retried_payload()

    first = handler.handle(wire)
    first_applied = handler.last_applied_new
    second = handler.handle(wire)            # the retransmission of the identical bytes
    second_applied = handler.last_applied_new
    third = handler.handle(wire)

    assert first == second == third, "each receipt is acknowledged identically and correlates to the one commandId"
    assert first[1] == "ACCEPTED"
    assert first_applied is True
    assert second_applied is False, "the duplicate is not applied again"
    assert device.write_capabilities["LED"].actuator.state is True, "the final state equals the commanded absolute state"


def test_a_retried_command_keeps_its_original_timestamp_so_it_is_judged_by_the_original_age_not_a_fresh_one():
    device = make_device()
    handler = CommandHandler(device, stale_window_seconds=30, clock=lambda: NOW)
    wire = retried_payload(timestamp=int(NOW) - 40)       # stamped 40 s ago and never refreshed by a retry

    assert handler.handle(wire)[1:] == ("REJECTED", "STALE")
    # the identical retransmission is the same rejection: a retry cannot turn an expired command into a fresh one
    assert handler.handle(wire)[1:] == ("REJECTED", "STALE")
    assert device.write_capabilities["LED"].actuator.state is False


def test_the_duplicate_history_is_bounded_so_a_very_old_commandid_can_be_applied_again_by_the_simulator():
    """Simulator-only property, stated so nobody mistakes it for device behaviour: the history is a bounded LRU."""
    device = make_device()
    handler = CommandHandler(device, clock=lambda: NOW, max_history=2)
    handler.handle(retried_payload(command_id="a"))
    handler.handle(retried_payload(command_id="b"))
    handler.handle(retried_payload(command_id="c"))        # evicts "a"
    device.write_capabilities["LED"].actuator.state = False
    handler.handle(retried_payload(command_id="a"))
    assert handler.last_applied_new is True


# ----------------------------------------------------------------------------- P4-121: the firmware check is modelled in the TEST

FIRMWARE_STALENESS_WINDOW_SECONDS = 30      # firmware/src/main.cpp COMMAND_STALENESS_WINDOW_SECONDS (read only, not modified)


def firmware_accepts(device_now, command_timestamp):
    """The committed firmware check: STALE when |now - timestamp| > 30, in both directions."""
    return abs(device_now - command_timestamp) <= FIRMWARE_STALENESS_WINDOW_SECONDS


def test_the_test_side_model_of_the_firmware_check_is_absolute_and_thirty_seconds():
    assert firmware_accepts(NOW, NOW - 30)
    assert not firmware_accepts(NOW, NOW - 31)
    assert firmware_accepts(NOW, NOW + 30)
    assert not firmware_accepts(NOW, NOW + 31), "a timestamp in the future is also stale for the firmware"


def test_the_simulator_default_now_equals_the_firmware_check_in_both_directions():
    """P5.5 (decision D4) changed this test, on purpose: it used to document that the simulator (300 s default, one direction) accepted what the
    firmware model rejects. The simulator now applies the firmware's rule (absolute difference, 30 s), so the same commands are accepted and
    rejected by both. Simulator evidence is still never presented as firmware evidence."""
    device = make_device()
    handler = CommandHandler(device, clock=lambda: NOW)          # DEFAULT stale window
    sixty_seconds_old = retried_payload(timestamp=int(NOW) - 60)
    assert not firmware_accepts(NOW, NOW - 60)
    assert handler.handle(sixty_seconds_old)[1:] == ("REJECTED", "STALE"), "the simulator default window is now the firmware's 30 s"
    # and in the other direction the simulator now rejects a future timestamp too, exactly like the firmware
    future = retried_payload(command_id="future", timestamp=int(NOW) + 60)
    assert not firmware_accepts(NOW, NOW + 60)
    assert handler.handle(future)[1:] == ("REJECTED", "STALE")
    # the boundary is shared: 30 s either side is accepted, 31 s is not
    for offset in (-30, -29, 0, 29, 30):
        fresh = retried_payload(command_id=f"fresh-{offset}", timestamp=int(NOW) + offset)
        assert firmware_accepts(NOW, NOW + offset)
        assert handler.handle(fresh)[1] == "ACCEPTED", offset
    for offset in (-31, 31):
        stale = retried_payload(command_id=f"stale-{offset}", timestamp=int(NOW) + offset)
        assert not firmware_accepts(NOW, NOW + offset)
        assert handler.handle(stale)[1:] == ("REJECTED", "STALE"), offset


def test_a_command_at_the_backend_window_ceiling_survives_the_firmware_model_with_the_budgeted_margin():
    ceiling = 20                                                  # CommandDeliveryLimits.MAX_DELIVERY_WINDOW
    truncation, clock_skew, network = 1, 5, 2                     # the budget of backend R11
    for error in (-clock_skew, 0, clock_skew):
        device_now = NOW + ceiling + truncation + network + error
        assert firmware_accepts(device_now, NOW), f"device clock error {error}"
    assert not firmware_accepts(NOW + ceiling + truncation + network + clock_skew + 3, NOW), \
        "three further seconds (an attempt after expires_at) would be rejected, which is why none ever starts there"
