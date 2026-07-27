from dataclasses import dataclass


@dataclass
class SimulatorConfig:
    """Minimal foundation configuration dataclass for the NEXUS simulator."""

    version: str = "0.1.0"
    is_initialized: bool = True
