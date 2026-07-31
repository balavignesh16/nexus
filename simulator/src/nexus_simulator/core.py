from .config import SimulatorConfig
from .client import NexusClient
from .publishers import ConsolePublisher
from .scheduler import SimulatorScheduler

class CompositePublisher:
    def __init__(self, *publishers):
        self.publishers = publishers

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> tuple[bool, float]:
        # Return the success and latency of the last publisher (HTTPPublisher)
        # In a real system you might want to aggregate these, but this is simple enough.
        success = True
        latency = 0.0
        for p in self.publishers:
            s, l = p.publish(device_id, sensor_type, value, unit)
            success = success and s
            latency = max(latency, l)
        return success, latency

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
            interval_seconds=config.publish_interval_seconds,
            refresh_interval_seconds=config.device_refresh_interval_seconds,
            max_workers=config.max_workers,
            batch_size=config.batch_size
        )
        scheduler.run()
