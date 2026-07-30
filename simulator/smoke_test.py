import json
from unittest.mock import patch
from nexus_simulator.core import main

# Mock active devices
MOCK_DEVICES = [
    {"id": "111", "deviceType": "TEMPERATURE_SENSOR"},
    {"id": "222", "deviceType": "GAS_SENSOR"},
    {"id": "333", "deviceType": "MOTION_SENSOR"}
]

with patch('nexus_simulator.client.NexusClient.get_active_devices', return_value=MOCK_DEVICES):
    with patch('time.sleep', side_effect=[None, InterruptedError]): # Let it run one cycle
        try:
            main()
        except InterruptedError:
            pass