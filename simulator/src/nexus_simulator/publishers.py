from typing import Protocol, Tuple
import time
import uuid

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
        # The exact body of the last submission, kept so a test can model a retransmission (P4.5).
        self.last_body: bytes | None = None

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str,
                message_id: str | None = None) -> Tuple[bool, float]:
        import json
        import datetime

        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

        payload = {
            "deviceId": device_id,
            "timestamp": timestamp,
            "sensorType": sensor_type,
            "value": value,
            "unit": unit,
            "messageId": message_id if message_id is not None else str(uuid.uuid4()),
        }
        self.last_body = json.dumps(payload).encode('utf-8')
        return self._post(device_id, self.last_body)

    def republish_last(self) -> Tuple[bool, float]:
        """Test hook: submit the last body again, byte for byte (same timestamp, same ``messageId``)."""
        if self.last_body is None:
            return False, 0.0
        return self._post("", self.last_body)

    def _post(self, device_id: str, data: bytes) -> Tuple[bool, float]:
        import urllib.request

        start_time = time.time()
        url = f"{self.backend_url}/telemetry"
        req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})

        try:
            with urllib.request.urlopen(req, timeout=5.0) as response:
                latency = (time.time() - start_time) * 1000
                # 201: stored. 200: the backend recognised a repeated messageId and did nothing (a successful no-op).
                if response.status in (200, 201):
                    return True, latency
                else:
                    print(f"[{device_id}] Failed to ingest telemetry. HTTP {response.status}")
                    return False, latency
        except Exception as e:
            latency = (time.time() - start_time) * 1000
            print(f"[{device_id}] HTTPPublisher Error: {e}")
            return False, latency
