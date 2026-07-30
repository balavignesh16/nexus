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
        # In M3.2, this will POST to the backend /telemetry endpoint.
        pass
