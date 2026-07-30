from dataclasses import dataclass


@dataclass
class SimulatorConfig:
    """Minimal foundation configuration dataclass for the NEXUS simulator."""

    version: str = "0.1.0"
    is_initialized: bool = True
    backend_url: str = "http://localhost:8080/api/v1"
    publish_interval_seconds: float = 5.0
