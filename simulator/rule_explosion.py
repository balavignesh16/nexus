import requests
import uuid
import time

BASE_URL = "http://localhost:8080/api/v1"

def create_rules(count):
    print(f"Creating {count} rules...")
    for i in range(count):
        req = {
            "name": f"Load Rule {i}",
            "enabled": True,
            "priority": i,
            "conditions": [
                {
                    "field": "SENSOR_TYPE",
                    "operator": "EQ",
                    "expectedValue": "TEMPERATURE_SENSOR"
                },
                {
                    "field": "VALUE",
                    "operator": "GT",
                    "expectedValue": "100.0" # Make sure it doesn't match to avoid flooding match store
                }
            ],
            "actions": [
                {
                    "actionType": "CreateAlert",
                    "parameters": {"severity": "INFO"}
                }
            ],
            "metadata": {}
        }
        resp = requests.post(f"{BASE_URL}/rules", json=req)
        if resp.status_code != 201:
            print(f"Failed to create rule {i}: {resp.status_code}")

def create_device():
    site_id = requests.post(f"{BASE_URL}/sites", json={"name": "Rule Site", "description": "test"}).json()["id"]
    building_id = requests.post(f"{BASE_URL}/sites/{site_id}/buildings", json={"name": "Rule Bldg", "description": "test"}).json()["id"]
    space_id = requests.post(f"{BASE_URL}/buildings/{building_id}/spaces", json={"name": "Rule Space", "description": "test"}).json()["id"]
    
    req = {
        "name": f"Rule Device",
        "deviceType": "TEMPERATURE_SENSOR",
        "manufacturer": "NEXUS",
        "model": "RULE-01",
        "serialNumber": f"SN-RULE-{uuid.uuid4().hex[:8]}"
    }
    device_id = requests.post(f"{BASE_URL}/spaces/{space_id}/devices", json=req).json()["id"]
    requests.put(f"{BASE_URL}/devices/{device_id}", json={"name": "Rule Device", "status": "ACTIVE"})
    return device_id

def send_telemetry(device_id, count):
    start = time.time()
    for _ in range(count):
        payload = {
            "deviceId": device_id,
            "timestamp": "2026-07-31T00:00:00Z",
            "sensorType": "TEMPERATURE_SENSOR",
            "value": 25.0,
            "unit": "CELSIUS"
        }
        requests.post(f"{BASE_URL}/telemetry", json=payload)
    return time.time() - start

if __name__ == "__main__":
    device_id = create_device()
    
    # Test with 1 rule
    create_rules(1)
    duration = send_telemetry(device_id, 100)
    print(f"1 Rule: 100 msgs in {duration:.2f}s ({100/duration:.2f} msg/s)")
    
    # Test with 10 rules
    create_rules(9)
    duration = send_telemetry(device_id, 100)
    print(f"10 Rules: 100 msgs in {duration:.2f}s ({100/duration:.2f} msg/s)")
    
    # Test with 100 rules
    create_rules(90)
    duration = send_telemetry(device_id, 100)
    print(f"100 Rules: 100 msgs in {duration:.2f}s ({100/duration:.2f} msg/s)")
    
    # Test with 1000 rules
    create_rules(900)
    duration = send_telemetry(device_id, 100)
    print(f"1000 Rules: 100 msgs in {duration:.2f}s ({100/duration:.2f} msg/s)")
