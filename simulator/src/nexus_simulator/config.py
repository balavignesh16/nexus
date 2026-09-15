import os
from dataclasses import dataclass, field
from typing import Optional


def _default_instance_id() -> Optional[str]:
    # Unset by default: the production simulator is a single-instance-per-
    # device-set operational model (mirrors the backend's own single
    # nexus-backend-sub client). Set only for deliberate multi-instance
    # scenarios (e.g. isolated parallel test runs) to avoid MQTT client_id
    # collisions between them.
    return os.environ.get("SIMULATOR_INSTANCE_ID") or None


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

    # Phase 12: per-device MQTT client identity suffix and command staleness.
    instance_id: Optional[str] = field(default_factory=_default_instance_id)
    command_stale_window_seconds: float = 300.0
