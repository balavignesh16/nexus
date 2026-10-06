"""Regression: live ACK tests must not target an arbitrary active device."""

import pytest

from tests import test_integration as live


def test_relay_selection_uses_explicit_running_simulator_serial(monkeypatch):
    orphan = {"id": "orphan", "serialNumber": "SIM-OLD-RELAY", "mode": "SIMULATED",
              "capabilities": [{"capabilityType": "RELAY", "direction": "WRITE"}]}
    running = {"id": "running", "serialNumber": "SIM-ESP32-ACTUATOR-01", "mode": "SIMULATED",
               "capabilities": [{"capabilityType": "RELAY", "direction": "WRITE"}]}
    monkeypatch.setenv("NEXUS_LIVE_RELAY_SERIAL", "SIM-ESP32-ACTUATOR-01")
    monkeypatch.setattr(live, "_get", lambda path: [orphan, running])

    assert live._find_simulated_device_with_write_capability("RELAY")["id"] == "running"


def test_live_selection_requires_explicit_simulator_identity(monkeypatch):
    monkeypatch.delenv("NEXUS_LIVE_LED_SERIAL", raising=False)
    monkeypatch.setattr(live, "_get", lambda path: [{"id": "arbitrary"}])

    with pytest.raises(pytest.skip.Exception, match="NEXUS_LIVE_LED_SERIAL"):
        live._find_simulated_device_with_write_capability("LED")
