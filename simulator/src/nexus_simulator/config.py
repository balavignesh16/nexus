from dataclasses import dataclass


@dataclass
class SimulatorConfig:
    """Minimal foundation configuration dataclass for the NEXUS simulator."""

    version: str = "0.1.0"
    is_initialized: bool = True
    backend_url: str = "http://localhost:8080/api/v1"
    publish_interval_seconds: float = 5.0
    device_refresh_interval_seconds: float = 60.0
    max_workers: int = 50
    batch_size: int = 10
    
    publish_mode: str = "mqtt"
    mqtt_broker: str = "localhost"
    mqtt_port: int = 1883
    mqtt_topic_template: str = "nexus/devices/{device_id}/telemetry"
    mqtt_qos: int = 1
