from typing import Protocol, Tuple
import time

class TelemetryPublisher(Protocol):
    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> Tuple[bool, float]:
        ...


class ConsolePublisher:
    """Logs telemetry to the console."""

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> Tuple[bool, float]:
        start = time.time()
        print(f"[{device_id}] {sensor_type}: {value} {unit}")
        return True, (time.time() - start) * 1000


class HTTPPublisher:
    """HTTP Publisher for Telemetry."""

    def __init__(self, backend_url: str):
        self.backend_url = backend_url

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> Tuple[bool, float]:
        import urllib.request
        import json
        import datetime
        
        start_time = time.time()
        url = f"{self.backend_url}/telemetry"
        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
        
        payload = {
            "deviceId": device_id,
            "timestamp": timestamp,
            "sensorType": sensor_type,
            "value": value,
            "unit": unit
        }
        
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
        
        try:
            with urllib.request.urlopen(req, timeout=5.0) as response:
                latency = (time.time() - start_time) * 1000
                if response.status == 201:
                    return True, latency
                else:
                    print(f"[{device_id}] Failed to ingest telemetry. HTTP {response.status}")
                    return False, latency
        except Exception as e:
            latency = (time.time() - start_time) * 1000
            print(f"[{device_id}] HTTPPublisher Error: {e}")
            return False, latency
