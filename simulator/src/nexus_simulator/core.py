from .config import SimulatorConfig
from .client import NexusClient
from .publishers import ConsolePublisher
from .scheduler import SimulatorScheduler

class CompositePublisher:
    def __init__(self, *publishers):
        self.publishers = publishers

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> None:
        for p in self.publishers:
            p.publish(device_id, sensor_type, value, unit)

def main() -> None:
    """Main entry point for the NEXUS Simulator."""
    config = SimulatorConfig()
    print(f"NEXUS Simulator Foundation Initialized (v{config.version})")
    print(f"Status: {'Active' if config.is_initialized else 'Inactive'}")
    
    if config.is_initialized:
        client = NexusClient(backend_url=config.backend_url)
        console_pub = ConsolePublisher()
        from .publishers import HTTPPublisher
        http_pub = HTTPPublisher(backend_url=config.backend_url)
        
        publisher = CompositePublisher(console_pub, http_pub)
        
        scheduler = SimulatorScheduler(
            client=client, 
            publisher=publisher, 
            interval_seconds=config.publish_interval_seconds
        )
        scheduler.run()
