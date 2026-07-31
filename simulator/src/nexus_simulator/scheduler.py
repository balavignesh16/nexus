import time
from typing import Dict, Any, List
import concurrent.futures
import threading

from .generators import TelemetryGenerator, TemperatureGenerator, GasGenerator, MotionGenerator
from .publishers import TelemetryPublisher
from .client import NexusClient


class SimulatorScheduler:
    def __init__(self, client: NexusClient, publisher: TelemetryPublisher, 
                 interval_seconds: float, refresh_interval_seconds: float = 60.0,
                 max_workers: int = 50, batch_size: int = 10):
        self.client = client
        self.publisher = publisher
        self.interval_seconds = interval_seconds
        self.refresh_interval_seconds = refresh_interval_seconds
        self.max_workers = max_workers
        self.batch_size = batch_size
        
        # State: device_id -> generator
        self.device_generators: Dict[str, TelemetryGenerator] = {}
        
        self.running = False
        self.last_sync_time = 0.0
        
        # Metrics
        self.metrics_lock = threading.Lock()
        self.metrics = {
            "generated": 0,
            "published": 0,
            "failures": 0,
            "latency_sum": 0.0,
            "publish_count": 0
        }

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
        try:
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
                        self.device_generators[d_id] = None
            
            # Remove generators for devices that are no longer active
            obsolete_ids = set(self.device_generators.keys()) - active_ids
            for obs_id in obsolete_ids:
                del self.device_generators[obs_id]
                
            self.last_sync_time = time.time()
        except Exception as e:
            print(f"Failed to sync devices: {e}")

    def process_batch(self, batch: List[str]) -> None:
        for device_id in batch:
            if not self.running:
                break
                
            generator = self.device_generators.get(device_id)
            if generator:
                val = generator.generate()
                # Default unit for simplicity; a robust system would map types to units
                unit = "RAW"
                if isinstance(generator, TemperatureGenerator):
                    unit = "CELSIUS"
                elif isinstance(generator, GasGenerator):
                    unit = "PPM"
                
                sensor_type = type(generator).__name__.replace("Generator", "").upper() + "_SENSOR"
                
                success, latency = self.publisher.publish(device_id, sensor_type, val, unit)
                
                with self.metrics_lock:
                    self.metrics["generated"] += 1
                    if success:
                        self.metrics["published"] += 1
                    else:
                        self.metrics["failures"] += 1
                    self.metrics["latency_sum"] += latency
                    self.metrics["publish_count"] += 1

    def print_metrics(self) -> None:
        with self.metrics_lock:
            generated = self.metrics["generated"]
            published = self.metrics["published"]
            failures = self.metrics["failures"]
            publish_count = self.metrics["publish_count"]
            avg_latency = self.metrics["latency_sum"] / publish_count if publish_count > 0 else 0
            
        print(f"\n--- Simulator Metrics ---")
        print(f"Devices simulated     : {len(self.device_generators)}")
        print(f"Telemetry generated   : {generated}")
        print(f"Telemetry published   : {published}")
        print(f"Publish failures      : {failures}")
        print(f"Average latency       : {avg_latency:.2f} ms")
        print(f"-------------------------\n")

    def run(self) -> None:
        self.running = True
        print(f"Simulator scheduler started. Polling every {self.interval_seconds}s.")
        print(f"Device discovery every {self.refresh_interval_seconds}s.")
        print(f"Concurrency: {self.max_workers} workers, {self.batch_size} batch size.")
        
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=self.max_workers)
        
        try:
            while self.running:
                loop_start = time.time()
                
                if time.time() - self.last_sync_time >= self.refresh_interval_seconds:
                    self.sync_devices()
                    self.print_metrics()
                
                device_ids = [d for d, g in self.device_generators.items() if g is not None]
                
                # Create batches
                batches = [device_ids[i:i + self.batch_size] for i in range(0, len(device_ids), self.batch_size)]
                
                futures = []
                for batch in batches:
                    futures.append(executor.submit(self.process_batch, batch))
                    
                # We don't block on futures here, they run concurrently.
                # The loop sleeps until the next interval.
                
                elapsed = time.time() - loop_start
                sleep_time = max(0.0, self.interval_seconds - elapsed)
                time.sleep(sleep_time)
                
        except KeyboardInterrupt:
            print("\nSimulator stopping. Gracefully shutting down...")
        finally:
            self.running = False
            executor.shutdown(wait=True)
            self.print_metrics()
            print("Simulator shutdown complete.")
