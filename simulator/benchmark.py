import time
import requests
import asyncio
import aiohttp
import uuid
import sys
import paho.mqtt.client as mqtt
from datetime import datetime, timezone
import json

BASE_URL = "http://localhost:8080/api/v1"
MQTT_HOST = "localhost"
MQTT_PORT = 1883

def create_site_building_space():
    site_id = requests.post(f"{BASE_URL}/sites", json={"name": "Bench Site", "description": "test"}).json()["id"]
    building_id = requests.post(f"{BASE_URL}/sites/{site_id}/buildings", json={"name": "Bench Bldg", "description": "test"}).json()["id"]
    space_id = requests.post(f"{BASE_URL}/buildings/{building_id}/spaces", json={"name": "Bench Space", "description": "test"}).json()["id"]
    return space_id

def create_device(space_id, index):
    req = {
        "name": f"Benchmark Device {index}",
        "deviceType": "TEMPERATURE_SENSOR",
        "manufacturer": "NEXUS",
        "model": "BENCH-01",
        "serialNumber": f"SN-BENCH-{uuid.uuid4().hex[:8]}"
    }
    resp = requests.post(f"{BASE_URL}/spaces/{space_id}/devices", json=req)
    if resp.status_code != 201:
        print(f"Failed to create device {index}: {resp.status_code} - {resp.text}")
        return None
    
    device_id = resp.json()["id"]
    # Activate
    activate_req = {
        "name": f"Benchmark Device {index}",
        "status": "ACTIVE",
        "description": "Benchmark"
    }
    requests.put(f"{BASE_URL}/devices/{device_id}", json=activate_req)
    return device_id

async def async_http_worker(session, device_ids, num_messages):
    latencies = []
    for _ in range(num_messages):
        for device_id in device_ids:
            payload = {
                "deviceId": device_id,
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "sensorType": "TEMPERATURE_SENSOR",
                "value": 25.0,
                "unit": "CELSIUS"
            }
            start = time.time()
            async with session.post(f"{BASE_URL}/telemetry", json=payload) as response:
                await response.read()
                latencies.append(time.time() - start)
    return latencies

async def run_http_benchmark(device_ids, num_messages):
    async with aiohttp.ClientSession() as session:
        return await async_http_worker(session, device_ids, num_messages)

def run_mqtt_benchmark(device_ids, num_messages):
    client = mqtt.Client(client_id=f"bench-pub-{uuid.uuid4()}")
    client.connect(MQTT_HOST, MQTT_PORT)
    
    latencies = []
    
    for _ in range(num_messages):
        for device_id in device_ids:
            payload = {
                "deviceId": device_id,
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "sensorType": "TEMPERATURE_SENSOR",
                "value": 25.0,
                "unit": "CELSIUS"
            }
            start = time.time()
            client.publish(f"nexus/devices/{device_id}/telemetry", json.dumps(payload), qos=0)
            latencies.append(time.time() - start)
    
    client.disconnect()
    return latencies

def setup_devices(count):
    space_id = create_site_building_space()
    device_ids = []
    for i in range(count):
        d_id = create_device(space_id, i)
        if d_id:
            device_ids.append(d_id)
    return device_ids

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python benchmark.py <http|mqtt> <num_devices>")
        sys.exit(1)
        
    protocol = sys.argv[1].lower()
    num_devices = int(sys.argv[2])
    
    print(f"Setting up {num_devices} devices...")
    device_ids = setup_devices(num_devices)
    if not device_ids:
        print("Failed to setup devices")
        sys.exit(1)
        
    print(f"Running {protocol.upper()} benchmark with {len(device_ids)} devices...")
    # 5 messages per device for the benchmark to get an average
    messages_per_device = 5
    
    start_time = time.time()
    
    if protocol == "http":
        latencies = asyncio.run(run_http_benchmark(device_ids, messages_per_device))
    elif protocol == "mqtt":
        latencies = run_mqtt_benchmark(device_ids, messages_per_device)
    else:
        print("Invalid protocol")
        sys.exit(1)
        
    total_time = time.time() - start_time
    total_messages = len(latencies)
    
    avg_latency = sum(latencies) / total_messages
    latencies.sort()
    p95 = latencies[int(total_messages * 0.95)]
    p99 = latencies[int(total_messages * 0.99)]
    throughput = total_messages / total_time
    
    print(f"\n--- {protocol.upper()} Benchmark Results ({num_devices} devices) ---")
    print(f"Total Messages: {total_messages}")
    print(f"Total Time: {total_time:.2f} s")
    print(f"Throughput: {throughput:.2f} msg/sec")
    print(f"Avg Latency: {avg_latency * 1000:.2f} ms")
    print(f"P95 Latency: {p95 * 1000:.2f} ms")
    print(f"P99 Latency: {p99 * 1000:.2f} ms")
