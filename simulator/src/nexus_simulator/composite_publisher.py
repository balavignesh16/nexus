import logging
from nexus_simulator.publishers import TelemetryPublisher

logger = logging.getLogger(__name__)

class CompositePublisher(TelemetryPublisher):
    """Publishes telemetry to multiple publishers sequentially."""

    def __init__(self, publishers: list[TelemetryPublisher]):
        self.publishers = publishers

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> tuple[bool, float]:
        all_success = True
        max_latency = 0.0
        for publisher in self.publishers:
            try:
                success, latency = publisher.publish(device_id, sensor_type, value, unit)
                if not success:
                    all_success = False
                max_latency = max(max_latency, latency)
            except Exception as e:
                logger.error(f"Error publishing via {publisher.__class__.__name__}: {e}")
                all_success = False
                
        return all_success, max_latency

    def close(self):
        for publisher in self.publishers:
            if hasattr(publisher, 'close'):
                try:
                    publisher.close()
                except Exception as e:
                    logger.error(f"Error closing {publisher.__class__.__name__}: {e}")
