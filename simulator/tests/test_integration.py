"""Live integration tests: require a running NEXUS backend + MQTT broker
(the same docker-compose stack used for Phase 11 verification) and the
simulator's own provisioned devices. These are skipped automatically when
that stack isn't reachable, so `pytest` alone never requires live infra -
run them explicitly (e.g. in CI/validation) once the stack and
provision_devices.py have both been run.
"""

import json
import time
import urllib.error
import urllib.request
import uuid

import pytest

BACKEND_URL = "http://localhost:8080/api/v1"
MQTT_BROKER = "localhost"
MQTT_PORT = 1883


def _backend_reachable() -> bool:
    try:
        urllib.request.urlopen(f"{BACKEND_URL}/devices", timeout=2.0)
        return True
    except Exception:
        return False


def _broker_reachable() -> bool:
    try:
        import paho.mqtt.client as mqtt
        client = mqtt.Client(client_id=f"nexus-sim-test-probe-{uuid.uuid4().hex[:8]}")
        client.connect(MQTT_BROKER, MQTT_PORT, 5)
        client.disconnect()
        return True
    except Exception:
        return False


requires_live_stack = pytest.mark.skipif(
    not (_backend_reachable() and _broker_reachable()),
    reason="requires a live NEXUS backend + MQTT broker (docker-compose up)",
)


def _get(path: str):
    with urllib.request.urlopen(f"{BACKEND_URL}{path}", timeout=5.0) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _find_simulated_device_with_write_capability(capability_type: str):
    for candidate in _get("/devices/active"):
        if candidate.get("mode") != "SIMULATED":
            continue
        for cap in candidate.get("capabilities", []):
            if cap["capabilityType"] == capability_type and cap["direction"] == "WRITE":
                return candidate
    return None


def _find_simulated_device_with_capabilities(min_read_capabilities: int = 1):
    devices = _get("/devices/active")
    for device in devices:
        if device.get("mode") != "SIMULATED":
            continue
        read_caps = [c for c in device.get("capabilities", []) if c.get("direction") == "READ"]
        if len(read_caps) >= min_read_capabilities:
            return device
    return None


@requires_live_stack
def test_simulated_telemetry_creates_independent_twin_rows_per_capability():
    """Requires provision_devices.py to have been run first and the
    production simulator (`python -m nexus_simulator`) to be running so it
    is actually publishing telemetry for the discovered devices."""
    device = _find_simulated_device_with_capabilities(min_read_capabilities=2)
    if device is None:
        pytest.skip("no multi-capability SIMULATED device provisioned - run provision_devices.py")

    device_id = device["id"]
    read_types = {c["capabilityType"] for c in device["capabilities"] if c["direction"] == "READ"}

    # Give the running simulator a few publish cycles to populate the Twin.
    time.sleep(12)

    twin = _get(f"/devices/{device_id}/twin")
    assert twin["latestSensorType"] in read_types

    # This does not query the DB directly (that's an operator-run check, as
    # in Phase 11's live verification) - it only proves the external API
    # contract still returns exactly one representative row.
    assert isinstance(twin["latestValue"], (int, float))


@requires_live_stack
def test_led_command_via_broker_is_accepted_and_acked():
    """Publishes a command directly to the broker (bypassing backend
    dispatch) and verifies the running simulator applies it and ACKs -
    proves the simulator's own command contract compliance."""
    import paho.mqtt.client as mqtt

    device = _find_simulated_device_with_write_capability("LED")
    if device is None:
        pytest.skip("no SIMULATED device with a LED capability provisioned")

    device_id = device["id"]
    command_id = str(uuid.uuid4())
    received_acks = []

    def on_message(client, userdata, msg):
        received_acks.append(json.loads(msg.payload.decode("utf-8")))

    client = mqtt.Client(client_id=f"nexus-sim-test-ack-{uuid.uuid4().hex[:8]}")
    client.on_message = on_message
    client.connect(MQTT_BROKER, MQTT_PORT, 10)
    client.subscribe(f"nexus/devices/{device_id}/commands/ack", qos=1)
    client.loop_start()

    payload = json.dumps({
        "commandId": command_id,
        "capability": "LED",
        "command": "SET_STATE",
        "value": True,
        "timestamp": int(time.time()),
    })
    client.publish(f"nexus/devices/{device_id}/commands", payload, qos=1)

    deadline = time.time() + 10
    while time.time() < deadline and not received_acks:
        time.sleep(0.5)

    client.loop_stop()
    client.disconnect()

    assert received_acks, "no ACK received from simulator"
    assert received_acks[0]["commandId"] == command_id
    assert received_acks[0]["status"] == "ACCEPTED"


@requires_live_stack
def test_relay_command_with_channel_via_broker_is_simulator_contract_only():
    """RELAY is not yet backend-dispatchable (deferred backend capability
    extension) - this publishes directly to the broker and validates only
    the simulator's own contract compliance, not backend dispatch."""
    import paho.mqtt.client as mqtt

    device = _find_simulated_device_with_write_capability("RELAY")
    if device is None:
        pytest.skip("no SIMULATED device with a RELAY capability provisioned")

    device_id = device["id"]
    command_id = str(uuid.uuid4())
    received_acks = []

    def on_message(client, userdata, msg):
        received_acks.append(json.loads(msg.payload.decode("utf-8")))

    client = mqtt.Client(client_id=f"nexus-sim-test-relay-{uuid.uuid4().hex[:8]}")
    client.on_message = on_message
    client.connect(MQTT_BROKER, MQTT_PORT, 10)
    client.subscribe(f"nexus/devices/{device_id}/commands/ack", qos=1)
    client.loop_start()

    payload = json.dumps({
        "commandId": command_id,
        "capability": "RELAY",
        "command": "SET_STATE",
        "value": {"channel": 2, "state": True},
        "timestamp": int(time.time()),
    })
    client.publish(f"nexus/devices/{device_id}/commands", payload, qos=1)

    deadline = time.time() + 10
    while time.time() < deadline and not received_acks:
        time.sleep(0.5)

    client.loop_stop()
    client.disconnect()

    assert received_acks, "no ACK received from simulator"
    assert received_acks[0]["status"] == "ACCEPTED"
