from .config import SimulatorConfig
from .client import NexusClient
from .publishers import ConsolePublisher
from .scheduler import SimulatorScheduler

def main() -> None:
    """Main entry point for the NEXUS Simulator."""
    config = SimulatorConfig()
    print(f"NEXUS Simulator Foundation Initialized (v{config.version})")
    print(f"Status: {'Active' if config.is_initialized else 'Inactive'}")
    
    if config.is_initialized:
        client = NexusClient(backend_url=config.backend_url)
        publisher = ConsolePublisher()
        scheduler = SimulatorScheduler(
            client=client, 
            publisher=publisher, 
            interval_seconds=config.publish_interval_seconds
        )
        scheduler.run()
