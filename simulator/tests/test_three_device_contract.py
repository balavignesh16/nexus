from pathlib import Path
import yaml

CONFIG = Path(__file__).parents[1] / "config" / "devices.yaml"

def test_three_esp_profiles_remain_compatible():
    devices = yaml.safe_load(CONFIG.read_text(encoding="utf-8"))["devices"]
    actual = {
        device["name"]: {
            (capability["type"], capability["direction"], capability.get("unit"))
            for capability in device["capabilities"]
        }
        for device in devices
    }
    assert actual == {
        "ESP32-SIM-001": {
            ("TEMPERATURE", "READ", "CELSIUS"), ("HUMIDITY", "READ", "PERCENT"),
            ("PRESSURE", "READ", "HPA"), ("MOTION", "READ", "BOOLEAN"),
            ("LED", "WRITE", "BOOLEAN"),
        },
        "ESP32-SIM-002": {
            ("GAS", "READ", "PPM"), ("RELAY", "WRITE", "BOOLEAN"),
            ("FAN", "WRITE", "BOOLEAN"), ("BUZZER", "WRITE", "BOOLEAN"),
        },
        "ESP32-SIM-003": {
            ("BUTTON", "READ", "BOOLEAN"), ("SERVO", "WRITE", "DEGREES"),
        },
    }
    relay = next(cap for cap in devices[1]["capabilities"] if cap["type"] == "RELAY")
    assert relay["metadata"]["channels"] == [1, 2, 3, 4]
