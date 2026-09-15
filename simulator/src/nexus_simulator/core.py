from .client import NexusClient
from .config import SimulatorConfig
from .scheduler import SimulatorScheduler


def main() -> None:
    """Main entry point for the NEXUS Simulator.

    Phase 12: capability-driven, multi-device, SIMULATED-only. Each
    discovered device owns its own MQTT client (created inside
    SimulatorScheduler.sync_devices via mqtt_publisher.py), so there is no
    longer one shared publisher constructed here.
    """
    config = SimulatorConfig()
    print(f"NEXUS Simulator Foundation Initialized (v{config.version})")
    print(f"Status: {'Active' if config.is_initialized else 'Inactive'}")
    print(f"Publish Mode: {config.publish_mode}")

    if config.is_initialized:
        client = NexusClient(backend_url=config.backend_url)

        scheduler = SimulatorScheduler(
            client=client,
            config=config,
            refresh_interval_seconds=config.device_refresh_interval_seconds,
            max_workers=config.max_workers,
        )
        scheduler.run()
