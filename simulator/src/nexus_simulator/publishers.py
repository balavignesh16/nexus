from typing import Protocol


class TelemetryPublisher(Protocol):
    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> None:
        ...


class ConsolePublisher:
    """Logs telemetry to the console."""

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> None:
        print(f"[{device_id}] {sensor_type}: {value} {unit}")


class HTTPPublisher:
    """Stub for HTTP Publisher (Deferred to M3.2)."""

    def __init__(self, backend_url: str):
        self.backend_url = backend_url

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> None:
        import urllib.request
        import json
        
        url = f"{self.backend_url}/telemetry"
        # The timestamp is generated as ISO8601 string compatible with Java Instant
        import datetime
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
                if response.status != 201:
                    print(f"[{device_id}] Failed to ingest telemetry. HTTP {response.status}")
        except Exception as e:
            print(f"[{device_id}] HTTPPublisher Error: {e}")
