from unittest.mock import patch

from nexus_simulator.core import main

# Mock SIMULATED, capability-driven devices (Phase 12 shape) - client.py's
# get_active_devices() already filters to mode=SIMULATED, so every mocked
# device here must carry that field to survive the filter.
MOCK_DEVICES = [
    {
        "id": "111",
        "name": "ESP32-SIM-SMOKE-001",
        "serialNumber": "SIM-ESP32-SIM-SMOKE-001",
        "mode": "SIMULATED",
        "capabilities": [
            {"capabilityType": "TEMPERATURE", "direction": "READ", "unit": "CELSIUS"},
            {"capabilityType": "HUMIDITY", "direction": "READ", "unit": "PERCENT"},
            {"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"},
        ],
    },
    {
        "id": "222",
        "name": "ESP32-SIM-SMOKE-002",
        "serialNumber": "SIM-ESP32-SIM-SMOKE-002",
        "mode": "SIMULATED",
        "capabilities": [
            {"capabilityType": "GAS", "direction": "READ", "unit": "PPM"},
        ],
    },
]

with patch('nexus_simulator.client.NexusClient.get_active_devices', return_value=MOCK_DEVICES):
    with patch('time.sleep', side_effect=[None, InterruptedError]):  # Let it run one cycle
        try:
            main()
        except InterruptedError:
            pass
