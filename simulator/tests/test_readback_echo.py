"""Command echo is emitted only for an explicit mapped simulator observation."""
import json
import time
from types import SimpleNamespace

from nexus_simulator.devices import SimulatedDevice
from nexus_simulator.mqtt_publisher import MQTTPublisher


def device():
    simulated = SimulatedDevice("device-1", "Sim", "sim-1", [
        {"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"},
        {"capabilityType": "RELAY", "direction": "WRITE", "unit": "BOOLEAN",
         "metadata": {"channels": [1, 2]}},
    ])
    simulated.readback_routes = [
        {"writeCapability": "LED", "writeSubtargetKey": "", "readSensorType": "LED_STATE",
         "readCapabilityKey": "led_state", "readUnit": "BOOLEAN", "selectorKey": "IDENTITY"},
        {"writeCapability": "RELAY", "writeSubtargetKey": "channel:2", "readSensorType": "RELAY_2_STATE",
         "readCapabilityKey": "relay_2_state", "readUnit": "BOOLEAN", "selectorKey": "IDENTITY"},
    ]
    return simulated


def test_only_mapped_channel_reports_actual_state():
    simulated = device()
    command = {"commandId": "command-1", "capability": "RELAY", "command": "SET_STATE",
               "value": {"channel": 1, "state": True}, "timestamp": int(time.time())}
    assert simulated.command_handler.handle(json.dumps(command).encode())[1] == "ACCEPTED"
    assert simulated.readback_observations("RELAY", command["value"]) == []
    command["commandId"] = "command-2"
    command["value"] = {"channel": 2, "state": True}
    assert simulated.command_handler.handle(json.dumps(command).encode())[1] == "ACCEPTED"
    assert simulated.readback_observations("RELAY", command["value"]) == [
        ("RELAY_2_STATE", "relay_2_state", True, "BOOLEAN")]
    assert simulated.write_capabilities["RELAY"].actuator.channels[1] is True


def test_duplicate_does_not_create_new_readback_and_unrelated_telemetry_has_no_echo():
    simulated = device()
    command = {"commandId": "command-led", "capability": "LED", "command": "SET_STATE",
               "value": True, "timestamp": int(time.time())}
    assert simulated.command_handler.handle(json.dumps(command).encode())[1] == "ACCEPTED"
    assert simulated.command_handler.last_applied_new
    assert simulated.readback_observations("LED", True) == [("LED_STATE", "led_state", True, "BOOLEAN")]
    simulated.command_handler.handle(json.dumps(command).encode())
    assert not simulated.command_handler.last_applied_new

    sent = []
    publisher = MQTTPublisher.__new__(MQTTPublisher)
    publisher.config = SimpleNamespace(mqtt_topic_template="nexus/devices/{device_id}/telemetry", mqtt_qos=1)
    publisher.client = SimpleNamespace(publish=lambda topic, payload, qos: (
        sent.append(json.loads(payload)) or SimpleNamespace(wait_for_publish=lambda: None)))
    assert publisher.publish("device-1", "LED_STATE", True, "BOOLEAN",
                             readback_command_id="command-led", capability_key="led_state")[0]
    assert sent[0]["readbackCommandId"] == "command-led"
    assert publisher.publish("device-1", "TEMPERATURE", 20, "CELSIUS")[0]
    assert "readbackCommandId" not in sent[1]
