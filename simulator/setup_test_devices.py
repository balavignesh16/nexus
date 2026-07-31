import urllib.request
import json
import uuid

BACKEND_URL = "http://localhost:8080/api/v1"

def create_site():
    print("Creating site...")
    data = json.dumps({"name": "Load Test Site", "description": "Site for mass load testing"}).encode('utf-8')
    req = urllib.request.Request(f"{BACKEND_URL}/sites", data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode('utf-8'))['id']

def create_building(site_id):
    print("Creating building...")
    data = json.dumps({"name": "Load Test Building", "description": "Building for load testing"}).encode('utf-8')
    req = urllib.request.Request(f"{BACKEND_URL}/sites/{site_id}/buildings", data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode('utf-8'))['id']

def create_space(building_id):
    print("Creating space...")
    data = json.dumps({"name": "Load Test Space", "description": "Space for load testing"}).encode('utf-8')
    req = urllib.request.Request(f"{BACKEND_URL}/buildings/{building_id}/spaces", data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode('utf-8'))['id']

def create_and_activate_devices(space_id, count, device_type, start_idx):
    print(f"Creating and activating {count} {device_type} devices...")
    for i in range(count):
        # Create
        data = json.dumps({
            "name": f"Test {device_type} {start_idx + i}",
            "deviceType": device_type,
            "manufacturer": "LoadGen",
            "model": "v1",
            "serialNumber": f"SN-{device_type}-{uuid.uuid4().hex[:8]}",
            "description": "Auto-generated for M3.3 load testing"
        }).encode('utf-8')
        req = urllib.request.Request(f"{BACKEND_URL}/spaces/{space_id}/devices", data=data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            device_id = json.loads(response.read().decode('utf-8'))['id']
            
        # Activate
        data = json.dumps({
            "name": f"Test {device_type} {start_idx + i}",
            "status": "ACTIVE",
            "description": "Auto-generated for M3.3 load testing"
        }).encode('utf-8')
        req = urllib.request.Request(f"{BACKEND_URL}/devices/{device_id}", data=data, headers={'Content-Type': 'application/json'}, method='PUT')
        with urllib.request.urlopen(req) as response:
            pass
            
    print(f"Done creating {count} devices of type {device_type}.")

def main():
    try:
        site_id = create_site()
        building_id = create_building(site_id)
        space_id = create_space(building_id)
        
        # We need ~175 devices total based on the spec
        create_and_activate_devices(space_id, 100, "TEMPERATURE_SENSOR", 1)
        create_and_activate_devices(space_id, 50, "GAS_SENSOR", 1)
        create_and_activate_devices(space_id, 25, "MOTION_SENSOR", 1)
        
        print("Successfully provisioned all devices! You can now start the simulator.")
    except Exception as e:
        print(f"Error provisioning devices: {e}")

if __name__ == "__main__":
    main()
