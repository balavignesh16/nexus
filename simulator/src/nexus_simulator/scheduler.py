import time
from typing import Dict, Any

from .generators import TelemetryGenerator, TemperatureGenerator, GasGenerator, MotionGenerator
from .publishers import TelemetryPublisher
from .client import NexusClient


class SimulatorScheduler:
    def __init__(self, client: NexusClient, publisher: TelemetryPublisher, interval_seconds: float):
        self.client = client
        self.publisher = publisher
        self.interval_seconds = interval_seconds
        
        # State: device_id -> generator
        self.device_generators: Dict[str, TelemetryGenerator] = {}
        
        self.running = False

    def get_generator_for_type(self, device_type: str) -> TelemetryGenerator:
        if device_type == "TEMPERATURE_SENSOR":
            return TemperatureGenerator()
        elif device_type == "GAS_SENSOR":
            return GasGenerator()
        elif device_type == "MOTION_SENSOR":
            return MotionGenerator()
        else:
            return None

    def sync_devices(self) -> None:
        """Fetches active devices and initializes generators for new ones."""
        devices = self.client.get_active_devices()
        active_ids = set()
        
        for device in devices:
            d_id = device.get("id")
            active_ids.add(d_id)
            if d_id not in self.device_generators:
                gen = self.get_generator_for_type(device.get("deviceType"))
                if gen:
                    self.device_generators[d_id] = gen
                else:
                    print(f"Unsupported device type ignored: {device.get('deviceType')} for {d_id}")
                    # Assign None to prevent repeated checking
                    self.device_generators[d_id] = None
        
        # Remove generators for devices that are no longer active
        obsolete_ids = set(self.device_generators.keys()) - active_ids
        for obs_id in obsolete_ids:
            del self.device_generators[obs_id]

    def run(self) -> None:
        self.running = True
        print(f"Simulator scheduler started. Polling every {self.interval_seconds}s.")
        
        # Initial sync
        self.sync_devices()
        
        # We'll sync devices every 10 iterations to pick up new devices
        sync_counter = 0

        try:
            while self.running:
                sync_counter += 1
                if sync_counter >= 10:
                    self.sync_devices()
                    sync_counter = 0

                for device_id, generator in self.device_generators.items():
                    if generator:
                        val = generator.generate()
                        # Default unit for simplicity; a robust system would map types to units
                        unit = "RAW"
                        if isinstance(generator, TemperatureGenerator):
                            unit = "CELSIUS"
                        elif isinstance(generator, GasGenerator):
                            unit = "PPM"
                        
                        sensor_type = type(generator).__name__.replace("Generator", "").upper() + "_SENSOR"
                        self.publisher.publish(device_id, sensor_type, val, unit)
                        
                time.sleep(self.interval_seconds)
        except KeyboardInterrupt:
            print("Simulator stopped by user.")
            self.running = False
