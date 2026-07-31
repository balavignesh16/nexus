from .config import SimulatorConfig
from .client import NexusClient
from .scheduler import SimulatorScheduler
from .publishers import HTTPPublisher, ConsolePublisher
from .mqtt_publisher import MQTTPublisher
from .composite_publisher import CompositePublisher

def main() -> None:
    """Main entry point for the NEXUS Simulator."""
    config = SimulatorConfig()
    print(f"NEXUS Simulator Foundation Initialized (v{config.version})")
    print(f"Status: {'Active' if config.is_initialized else 'Inactive'}")
    print(f"Publish Mode: {config.publish_mode}")
    
    if config.is_initialized:
        client = NexusClient(backend_url=config.backend_url)
        console_pub = ConsolePublisher()
        
        publishers = [console_pub] # Always log to console
        
        if config.publish_mode in ("http", "composite"):
            publishers.append(HTTPPublisher(backend_url=config.backend_url))
            
        if config.publish_mode in ("mqtt", "composite"):
            publishers.append(MQTTPublisher(config))
            
        publisher = CompositePublisher(publishers)
        
        scheduler = SimulatorScheduler(
            client=client, 
            publisher=publisher, 
            interval_seconds=config.publish_interval_seconds,
            refresh_interval_seconds=config.device_refresh_interval_seconds,
            max_workers=config.max_workers,
            batch_size=config.batch_size
        )
        try:
            scheduler.run()
        finally:
            publisher.close()
