import concurrent.futures
import threading
import time
from typing import Dict, List

from .client import NexusClient
from .devices import SimulatedDevice
from .mqtt_publisher import MQTTPublisher
from .publishers import ConsolePublisher, HTTPPublisher


class SimulatorScheduler:
    """Capability-driven, multi-device simulator runtime.

    Discovers SIMULATED devices from the backend (client.py already filters
    out REAL devices), builds one SimulatedDevice per discovered device from
    its provisioned capabilities, and independently schedules every READ
    capability's telemetry publish. Each device owns exactly one MQTT
    client (mqtt_publisher.py) for telemetry, LWT/status, command
    subscription, and ACK - never one client per capability.
    """

    def __init__(self, client: NexusClient, config,
                 refresh_interval_seconds: float = 60.0, max_workers: int = 50):
        self.client = client
        self.config = config
        self.refresh_interval_seconds = refresh_interval_seconds
        self.max_workers = max_workers

        self.devices: Dict[str, SimulatedDevice] = {}

        self.running = False
        self.last_sync_time = 0.0

        self._console = ConsolePublisher()
        http_enabled = config.publish_mode in ("http", "composite")
        self._http = HTTPPublisher(config.backend_url) if http_enabled else None
        self._mqtt_enabled = config.publish_mode in ("mqtt", "composite")

        self.metrics_lock = threading.Lock()
        self.metrics = {
            "generated": 0,
            "published": 0,
            "failures": 0,
        }

    def sync_devices(self) -> None:
        """Fetches SIMULATED devices and builds/tears down SimulatedDevice
        instances (and their MQTT clients) for the delta."""
        try:
            device_dicts = self.client.get_active_devices()
            active_ids = set()

            for device_dict in device_dicts:
                device_id = device_dict.get("id")
                active_ids.add(device_id)
                if device_id in self.devices:
                    if hasattr(self.client, "get_readback_routes"):
                        self.devices[device_id].readback_routes = self.client.get_readback_routes(device_id)
                    continue

                sim_device = SimulatedDevice(
                    device_id=device_id,
                    name=device_dict.get("name", ""),
                    serial_number=device_dict.get("serialNumber", ""),
                    capabilities=device_dict.get("capabilities", []),
                    command_stale_window_seconds=self.config.command_stale_window_seconds,
                )

                if not sim_device.has_capabilities():
                    print(f"Device {device_id} has no supported provisioned capabilities "
                          "- skipping")
                    continue

                if hasattr(self.client, "get_readback_routes"):
                    sim_device.readback_routes = self.client.get_readback_routes(device_id)

                if self._mqtt_enabled:
                    sim_device.mqtt_publisher = MQTTPublisher(self.config, device_id, sim_device)

                self.devices[device_id] = sim_device

            obsolete_ids = set(self.devices.keys()) - active_ids
            for obsolete_id in obsolete_ids:
                device = self.devices.pop(obsolete_id)
                if device.mqtt_publisher:
                    device.mqtt_publisher.close()

            self.last_sync_time = time.time()
        except Exception as e:
            print(f"Failed to sync devices: {e}")

    def _publish_reading(self, device: SimulatedDevice, capability) -> None:
        value = capability.generator.generate()
        if value is None:
            # Event-driven capability (e.g. BUTTON) with no state change
            # this tick - nothing to publish.
            return

        unit = capability.unit
        if isinstance(value, bool):
            value = 1.0 if value else 0.0
            if unit in (None, "RAW"):
                unit = "BOOLEAN"

        with self.metrics_lock:
            self.metrics["generated"] += 1

        success = True
        device_id, sensor_type = device.device_id, capability.capability_type
        self._console.publish(device_id, sensor_type, value, unit)
        if self._http:
            http_success, _ = self._http.publish(device_id, sensor_type, value, unit)
            success = success and http_success
        if device.mqtt_publisher:
            mqtt_success, _ = device.mqtt_publisher.publish(device_id, sensor_type, value, unit)
            success = success and mqtt_success

        with self.metrics_lock:
            if success:
                self.metrics["published"] += 1
            else:
                self.metrics["failures"] += 1

    def print_metrics(self) -> None:
        with self.metrics_lock:
            generated = self.metrics["generated"]
            published = self.metrics["published"]
            failures = self.metrics["failures"]

        print("\n--- Simulator Metrics ---")
        print(f"Devices simulated     : {len(self.devices)}")
        print(f"Telemetry generated   : {generated}")
        print(f"Telemetry published   : {published}")
        print(f"Publish failures      : {failures}")
        print("-------------------------\n")

    def run(self) -> None:
        self.running = True
        print("Simulator scheduler started (capability-driven). "
              f"Polling every {self.config.publish_interval_seconds}s.")
        print(f"Device discovery every {self.refresh_interval_seconds}s.")

        executor = concurrent.futures.ThreadPoolExecutor(max_workers=self.max_workers)

        try:
            while self.running:
                loop_start = time.time()

                if time.time() - self.last_sync_time >= self.refresh_interval_seconds:
                    self.sync_devices()
                    self.print_metrics()

                tasks: List[tuple] = [
                    (device, capability)
                    for device in list(self.devices.values())
                    for capability in device.read_capabilities.values()
                ]

                for device, capability in tasks:
                    if not self.running:
                        break
                    executor.submit(self._publish_reading, device, capability)

                elapsed = time.time() - loop_start
                sleep_time = max(0.0, self.config.publish_interval_seconds - elapsed)
                time.sleep(sleep_time)

        except KeyboardInterrupt:
            print("\nSimulator stopping. Gracefully shutting down...")
        finally:
            self.running = False
            executor.shutdown(wait=True)
            for device in self.devices.values():
                if device.mqtt_publisher:
                    device.mqtt_publisher.close()
            self.print_metrics()
            print("Simulator shutdown complete.")
