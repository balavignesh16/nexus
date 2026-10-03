#!/usr/bin/env python3
"""
NEXUS Grand Simulation - end-to-end physical-intelligence demonstration.

Makes the CURRENT NEXUS platform behave as if the planned physical
installation is complete, by simulating three ESP32 devices that talk to
the real backend over the SAME MQTT/device/capability/command/ACK
contracts a real ESP32 will use later. No new architecture, no new
protocol, no direct DB writes - every effect travels through the real
NEXUS pipeline (telemetry -> twin -> event -> rule -> action -> command ->
ACK -> ActionHistory -> pipeline trace).

Reuses the existing simulator package (simulator/src/nexus_simulator) for
MQTT wiring, actuator state/validation, and command handling, and the
existing simulator/provision_devices.py for idempotent device/capability
provisioning - only the READ-capability value generators are replaced
here, with a small correlated PhysicalWorld model driving realistic,
scenario-directed telemetry instead of independent random walks.

Run with the simulator's own virtualenv interpreter (same one
start_nexus.py's simulator step uses):

    simulator\\.venv\\Scripts\\python.exe grand_simulation.py
    simulator\\.venv\\Scripts\\python.exe grand_simulation.py --duration 300

Requires the NEXUS backend + MQTT broker already running (see
start_nexus.py).
"""

import argparse
import random
import sys
import time
import urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
SIMULATOR_DIR = REPO_ROOT / "simulator"
SIMULATOR_SRC = SIMULATOR_DIR / "src"

for _p in (str(SIMULATOR_DIR), str(SIMULATOR_SRC)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

try:
    from nexus_simulator.client import NexusClient
    from nexus_simulator.config import SimulatorConfig
    from nexus_simulator.devices import SimulatedDevice
    from nexus_simulator.mqtt_publisher import MQTTPublisher
    import provision_devices as prov
except ImportError as exc:
    raise SystemExit(
        "Could not import the NEXUS simulator package. Run this script with "
        "the simulator's own virtualenv interpreter, e.g.:\n"
        "  simulator\\.venv\\Scripts\\python.exe grand_simulation.py\n"
        f"(import error: {exc})"
    )

BASELINE_GAS = 70.0


# ---------------------------------------------------------------------------
# Physical world: one small correlated environment shared by all 3 devices.
# ---------------------------------------------------------------------------

class PhysicalWorld:
    """Shared, correlated simulated lab environment. Actuator states are
    read directly from ESP32-ACTUATOR-01's real actuator objects (bound via
    bind_actuators) so FAN/RELAY state can physically couple back into
    temperature/gas - never an independent copy of actuator state."""

    def __init__(self):
        self.temperature = 24.0
        self.humidity = 55.0
        self.pressure = 1013.0
        self.gas = BASELINE_GAS
        self.motion = False
        self.button = False

        self._actuators = None
        self._heat_event_until = 0.0
        self._gas_incident_until = 0.0
        self._gas_target = BASELINE_GAS
        self._motion_until = 0.0

    def bind_actuators(self, write_capabilities):
        self._actuators = write_capabilities

    def _actuator_attr(self, capability, attr, default):
        wc = self._actuators.get(capability) if self._actuators else None
        if wc is None:
            return default
        return getattr(wc.actuator, attr, default)

    @property
    def fan_on(self) -> bool:
        return bool(self._actuator_attr("FAN", "state", False))

    def trigger_heat_event(self, seconds: float = 40.0) -> None:
        self._heat_event_until = time.time() + seconds

    def trigger_gas_incident(self, target: float = 550.0, seconds: float = 30.0) -> None:
        self._gas_incident_until = time.time() + seconds
        self._gas_target = target

    def trigger_motion(self, seconds: float = 20.0) -> None:
        self._motion_until = time.time() + seconds
        self.motion = True

    def clear_motion(self) -> None:
        self._motion_until = 0.0
        self.motion = False

    def press_button(self) -> None:
        self.button = True

    def release_button(self) -> None:
        self.button = False

    def tick(self) -> None:
        now = time.time()

        heat_target = 42.0 if now < self._heat_event_until else 23.0
        step = (heat_target - self.temperature) * 0.25 + random.uniform(-0.3, 0.3)
        if self.fan_on:
            step -= 0.35
        self.temperature = max(15.0, min(45.0, self.temperature + step))

        humidity_target = 55.0 - (self.temperature - 24.0) * 0.8
        self.humidity = max(25.0, min(80.0, self.humidity
                                       + (humidity_target - self.humidity) * 0.1
                                       + random.uniform(-0.5, 0.5)))

        self.pressure = max(990.0, min(1030.0, self.pressure + random.uniform(-0.3, 0.3)))

        if now < self._gas_incident_until:
            self.gas += (self._gas_target - self.gas) * 0.25 + random.uniform(-5, 5)
        else:
            decay_rate = 0.30 if self.fan_on else 0.12
            self.gas += (BASELINE_GAS - self.gas) * decay_rate + random.uniform(-4, 4)
        self.gas = max(10.0, min(1000.0, self.gas))

        if self.motion and now >= self._motion_until:
            self.motion = False

    def snapshot_actuators(self) -> dict:
        relay_wc = self._actuators.get("RELAY") if self._actuators else None
        relay_channels = dict(relay_wc.actuator.channels) if relay_wc else {}
        return {
            "LED": self._actuator_attr("LED", "state", False),
            "FAN": self._actuator_attr("FAN", "state", False),
            "BUZZER": self._actuator_attr("BUZZER", "state", False),
            "SERVO": self._actuator_attr("SERVO", "angle", 0),
            "RELAY": relay_channels,
        }


# ---------------------------------------------------------------------------
# World-driven telemetry generators (TelemetryGenerator protocol: .generate())
# ---------------------------------------------------------------------------

class _WorldReading:
    def __init__(self, world: PhysicalWorld, attr: str, round_to=None):
        self.world = world
        self.attr = attr
        self.round_to = round_to

    def generate(self):
        value = getattr(self.world, self.attr)
        return round(value, self.round_to) if self.round_to is not None else value


class WorldBoundDevice(SimulatedDevice):
    """SimulatedDevice whose READ capabilities are driven by a shared
    PhysicalWorld instead of independent random walks. WRITE capabilities
    (none on ENV-01/SAFETY-01) still use the base class unchanged."""

    def __init__(self, world: PhysicalWorld, device_id, name, serial_number, capabilities):
        self._world = world
        super().__init__(device_id, name, serial_number, capabilities)

    def _build_generator(self, capability_type: str):
        w = self._world
        if capability_type == "TEMPERATURE":
            return _WorldReading(w, "temperature", 2)
        if capability_type == "HUMIDITY":
            return _WorldReading(w, "humidity", 2)
        if capability_type == "PRESSURE":
            return _WorldReading(w, "pressure", 2)
        if capability_type == "GAS":
            return _WorldReading(w, "gas", 1)
        if capability_type == "MOTION":
            return _WorldReading(w, "motion")
        if capability_type == "BUTTON":
            return _WorldReading(w, "button")
        return None


# ---------------------------------------------------------------------------
# Provisioning: 3 devices + demonstration rules, both idempotent.
# ---------------------------------------------------------------------------

DEVICE_DEFS = [
    prov.DeviceConfig(name="ESP32-ENV-01", capabilities=[
        prov.CapabilityConfig(type="TEMPERATURE", direction="READ", unit="CELSIUS"),
        prov.CapabilityConfig(type="HUMIDITY", direction="READ", unit="PERCENT"),
        prov.CapabilityConfig(type="PRESSURE", direction="READ", unit="HPA"),
    ]),
    prov.DeviceConfig(name="ESP32-SAFETY-01", capabilities=[
        prov.CapabilityConfig(type="GAS", direction="READ", unit="PPM"),
        prov.CapabilityConfig(type="MOTION", direction="READ", unit="BOOLEAN"),
        prov.CapabilityConfig(type="BUTTON", direction="READ", unit="BOOLEAN"),
    ]),
    prov.DeviceConfig(name="ESP32-ACTUATOR-01", capabilities=[
        prov.CapabilityConfig(type="LED", direction="WRITE", unit="BOOLEAN"),
        prov.CapabilityConfig(type="BUZZER", direction="WRITE", unit="BOOLEAN"),
        prov.CapabilityConfig(type="SERVO", direction="WRITE", unit="DEGREES"),
        prov.CapabilityConfig(type="FAN", direction="WRITE", unit="BOOLEAN"),
        prov.CapabilityConfig(type="RELAY", direction="WRITE", unit="BOOLEAN",
                               metadata={"channels": [1, 2, 3, 4]}),
    ]),
]


def provision_devices() -> dict:
    """Idempotently ensures the 3 Grand Simulation devices exist, reusing
    provision_devices.py's site/building/space/device/capability logic."""
    site_id = prov.ensure_site()
    building_id = prov.ensure_building(site_id)
    space_id = prov.ensure_space(building_id)

    all_devices = prov._request("GET", "/devices") or []
    existing_by_serial = {d["serialNumber"]: d for d in all_devices if d.get("serialNumber")}

    device_ids = {}
    for cfg in DEVICE_DEFS:
        device = prov.ensure_device(space_id, existing_by_serial, cfg)
        prov.ensure_capabilities(device["id"], cfg)
        device_ids[cfg.name] = device["id"]
    return device_ids


def _device_cond(device_id: str) -> dict:
    return {"field": "DEVICE_ID", "operator": "EQ", "expectedValue": device_id}


def _cap_cond(operator: str, expected: str, capability_type: str) -> dict:
    return {"field": "VALUE", "operator": operator, "expectedValue": expected,
            "capabilityType": capability_type}


def _send_command(actuator_id: str, capability: str, value) -> dict:
    return {"actionType": "SEND_COMMAND",
            "parameters": {"deviceId": actuator_id, "capability": capability,
                            "command": "SET_STATE", "value": value}}


def build_rule_defs(device_ids: dict) -> list:
    env_id = device_ids["ESP32-ENV-01"]
    safety_id = device_ids["ESP32-SAFETY-01"]
    actuator_id = device_ids["ESP32-ACTUATOR-01"]

    return [
        {
            "name": "Grand Sim - High Temperature -> Fan On", "enabled": True, "priority": 10,
            "conditions": [_device_cond(env_id), _cap_cond("GT", "35", "TEMPERATURE")],
            "actions": [_send_command(actuator_id, "FAN", True)],
            "metadata": {}, "cooldownSeconds": 15,
        },
        {
            "name": "Grand Sim - Motion -> LED On", "enabled": True, "priority": 10,
            "conditions": [_device_cond(safety_id), _cap_cond("EQ", "true", "MOTION")],
            "actions": [_send_command(actuator_id, "LED", True)],
            "metadata": {}, "cooldownSeconds": 15,
        },
        {
            "name": "Grand Sim - Gas Alert -> Buzzer On", "enabled": True, "priority": 10,
            "conditions": [_device_cond(safety_id), _cap_cond("GT", "400", "GAS")],
            "actions": [_send_command(actuator_id, "BUZZER", True)],
            "metadata": {}, "cooldownSeconds": 15,
        },
        {
            "name": "Grand Sim - Gas+Motion Safety Event -> Buzzer+Fan", "enabled": True, "priority": 20,
            "conditions": [_device_cond(safety_id), _cap_cond("GT", "500", "GAS"),
                            _cap_cond("EQ", "true", "MOTION")],
            "actions": [_send_command(actuator_id, "BUZZER", True), _send_command(actuator_id, "FAN", True)],
            "metadata": {}, "cooldownSeconds": 15,
        },
        {
            "name": "Grand Sim - Button Press -> LED On", "enabled": True, "priority": 10,
            "conditions": [_device_cond(safety_id), _cap_cond("EQ", "true", "BUTTON")],
            "actions": [_send_command(actuator_id, "LED", True)],
            "metadata": {}, "cooldownSeconds": 15,
        },
        {
            "name": "Grand Sim - Critical Gas+Motion -> Relay Ch1", "enabled": True, "priority": 30,
            "conditions": [_device_cond(safety_id), _cap_cond("GT", "700", "GAS"),
                            _cap_cond("EQ", "true", "MOTION")],
            "actions": [_send_command(actuator_id, "RELAY", {"channel": 1, "state": True})],
            "metadata": {}, "cooldownSeconds": 15,
        },
        {
            "name": "Grand Sim - Recovery -> Fan Off", "enabled": True, "priority": 10,
            "conditions": [_device_cond(env_id), _cap_cond("LT", "26", "TEMPERATURE")],
            "actions": [_send_command(actuator_id, "FAN", False)],
            "metadata": {}, "cooldownSeconds": 15,
        },
    ]


def ensure_rules(device_ids: dict) -> None:
    existing = prov._request("GET", "/rules") or []
    existing_names = {r["name"] for r in existing}

    for rule in build_rule_defs(device_ids):
        if rule["name"] in existing_names:
            print(f"[NEXUS SIM] Rule already provisioned: {rule['name']}")
            continue
        try:
            prov._request("POST", "/rules", rule)
            print(f"[NEXUS SIM] Provisioned rule: {rule['name']}")
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")[:300]
            print(f"[NEXUS SIM] Failed to provision rule '{rule['name']}': HTTP {e.code} {detail}")


def check_backend(retries: int = 10, delay_seconds: float = 3.0) -> None:
    """Retries instead of failing immediately - when launched right after
    start_nexus.py's own fixed startup wait, the backend may still be
    finishing initialization for a few more seconds."""
    last_error = None
    for attempt in range(1, retries + 1):
        try:
            prov._request("GET", "/devices/active")
            return
        except Exception as e:
            last_error = e
            if attempt < retries:
                print(f"[NEXUS SIM] Backend not reachable yet (attempt {attempt}/{retries}), "
                      f"retrying in {delay_seconds:.0f}s...")
                time.sleep(delay_seconds)
    raise SystemExit(
        f"[NEXUS SIM] Cannot reach NEXUS backend at {prov.BACKEND_URL} after {retries} attempts.\n"
        "Start the platform first, e.g.:\n"
        "  python start_nexus.py\n"
        f"(last error: {last_error})"
    )


def fetch_simulated_devices(names) -> dict:
    client = NexusClient(backend_url=prov.BACKEND_URL)
    devices = client.get_active_devices()
    by_name = {d["name"]: d for d in devices if d["name"] in names}
    missing = set(names) - set(by_name)
    if missing:
        raise SystemExit(f"[NEXUS SIM] Provisioned devices not found as ACTIVE/SIMULATED: {missing}")
    return by_name


# ---------------------------------------------------------------------------
# Device/MQTT wiring
# ---------------------------------------------------------------------------

def build_devices(by_name: dict, config: SimulatorConfig):
    world = PhysicalWorld()

    a = by_name["ESP32-ACTUATOR-01"]
    actuator_device = SimulatedDevice(a["id"], a["name"], a.get("serialNumber", ""),
                                       a.get("capabilities", []))
    world.bind_actuators(actuator_device.write_capabilities)

    e = by_name["ESP32-ENV-01"]
    env_device = WorldBoundDevice(world, e["id"], e["name"], e.get("serialNumber", ""),
                                   e.get("capabilities", []))

    s = by_name["ESP32-SAFETY-01"]
    safety_device = WorldBoundDevice(world, s["id"], s["name"], s.get("serialNumber", ""),
                                      s.get("capabilities", []))

    devices = [env_device, safety_device, actuator_device]
    for device in devices:
        device.mqtt_publisher = MQTTPublisher(config, device.device_id, device)
        time.sleep(0.3)

    return world, devices, a["id"]


def publish_tick(devices) -> None:
    for device in devices:
        for cap_type, rc in device.read_capabilities.items():
            value = rc.generator.generate()
            if value is None:
                continue
            unit = rc.unit
            out_value = value
            if isinstance(value, bool):
                out_value = 1.0 if value else 0.0
                if unit in (None, "RAW"):
                    unit = "BOOLEAN"
            device.mqtt_publisher.publish(device.device_id, cap_type, out_value, unit)


# ---------------------------------------------------------------------------
# Manual command dispatch (real REST command path - Scenarios 6/7)
# ---------------------------------------------------------------------------

def dispatch_command(device_id: str, capability: str, value, label: str = "") -> None:
    body = {"capability": capability, "command": "SET_STATE", "value": value}
    try:
        result = prov._request("POST", f"/devices/{device_id}/commands", body)
        status = result.get("status", "UNKNOWN") if isinstance(result, dict) else "UNKNOWN"
        print(f"[NEXUS SIM] MANUAL COMMAND {capability}={value} {label}-> {status}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:200]
        print(f"[NEXUS SIM] MANUAL COMMAND {capability}={value} FAILED: HTTP {e.code} {detail}")


def demo_relay(actuator_id: str) -> None:
    dispatch_command(actuator_id, "RELAY", {"channel": 1, "state": True}, "(ch1 on) ")
    time.sleep(2)
    dispatch_command(actuator_id, "RELAY", {"channel": 2, "state": True}, "(ch2 on) ")
    time.sleep(2)
    dispatch_command(actuator_id, "RELAY", {"channel": 1, "state": False}, "(ch1 off) ")


def demo_servo(actuator_id: str) -> None:
    for angle in (0, 90, 180):
        dispatch_command(actuator_id, "SERVO", angle, f"({angle} deg) ")
        time.sleep(2)


def recovery_sequence(world: PhysicalWorld, actuator_id: str) -> None:
    world.clear_motion()
    world.release_button()
    dispatch_command(actuator_id, "BUZZER", False, "(recovery) ")
    dispatch_command(actuator_id, "LED", False, "(recovery) ")


# ---------------------------------------------------------------------------
# Scenario engine
# ---------------------------------------------------------------------------

def build_scenarios(world: PhysicalWorld, actuator_id: str) -> list:
    return [
        {"name": "NORMAL OPERATION", "duration": 20,
         "chain": "TELEMETRY -> TWIN (steady-state, no rule expected)",
         "on_enter": lambda: None},
        {"name": "HIGH TEMPERATURE", "duration": 40,
         "chain": "TELEMETRY -> EVENT -> RULE MATCH -> ACTION -> COMMAND -> ACK (FAN ON)",
         "on_enter": lambda: world.trigger_heat_event(40)},
        {"name": "MOTION DETECTED", "duration": 25,
         "chain": "TELEMETRY -> EVENT -> RULE MATCH -> ACTION -> COMMAND -> ACK (LED ON)",
         "on_enter": lambda: world.trigger_motion(20)},
        {"name": "GAS ALERT", "duration": 30,
         "chain": "TELEMETRY -> EVENT -> RULE MATCH -> ACTION -> COMMAND -> ACK (BUZZER ON)",
         "on_enter": lambda: world.trigger_gas_incident(550, 25)},
        {"name": "MULTI-CAPABILITY SAFETY EVENT (GAS+MOTION)", "duration": 35,
         "chain": "TELEMETRY -> EVENT -> MULTI-CAPABILITY RULE MATCH -> BUZZER+FAN (+RELAY CH1 if critical)",
         "on_enter": lambda: (world.trigger_gas_incident(850, 30), world.trigger_motion(30))},
        {"name": "RELAY CONTROL DEMO", "duration": 15,
         "chain": "MANUAL COMMAND -> MQTT -> SIMULATED ACTUATOR -> ACK (RELAY CH1/CH2)",
         "on_enter": lambda: demo_relay(actuator_id)},
        {"name": "SERVO CONTROL DEMO", "duration": 15,
         "chain": "MANUAL COMMAND -> MQTT -> SIMULATED ACTUATOR -> ACK (SERVO 0/90/180)",
         "on_enter": lambda: demo_servo(actuator_id)},
        {"name": "BUTTON PRESS EVENT", "duration": 15,
         "chain": "TELEMETRY -> EVENT -> RULE MATCH -> ACTION -> COMMAND -> ACK (LED ON)",
         "on_enter": lambda: world.press_button()},
        {"name": "RECOVERY", "duration": 30,
         "chain": "TELEMETRY -> EVENT -> RULE MATCH -> ACTION -> COMMAND -> ACK (FAN auto via rule; BUZZER/LED via manual command)",
         "on_enter": lambda: recovery_sequence(world, actuator_id)},
    ]


def print_scenario_banner(scenario: dict) -> None:
    print()
    print(f"[NEXUS SIM] SCENARIO: {scenario['name']}")
    print(f"[NEXUS SIM] EXPECTED CHAIN: {scenario['chain']}")
    print()


def print_snapshot(world: PhysicalWorld) -> None:
    a = world.snapshot_actuators()
    relay = a["RELAY"]
    relay_str = " ".join(f"CH{ch}:{'ON' if st else 'OFF'}" for ch, st in sorted(relay.items())) or "n/a"
    print(f"[NEXUS SIM] ENV-01      Temp={world.temperature:5.1f}C  Hum={world.humidity:4.1f}%  "
          f"Pressure={world.pressure:6.1f}hPa")
    print(f"[NEXUS SIM] SAFETY-01   Gas={world.gas:5.1f}ppm  "
          f"Motion={'TRUE' if world.motion else 'FALSE'}  Button={'TRUE' if world.button else 'FALSE'}")
    print(f"[NEXUS SIM] ACTUATOR-01 LED={'ON' if a['LED'] else 'OFF'}  FAN={'ON' if a['FAN'] else 'OFF'}  "
          f"BUZZER={'ON' if a['BUZZER'] else 'OFF'}  SERVO={a['SERVO']}deg  RELAY[{relay_str}]")


def run_simulation(world: PhysicalWorld, devices, scenarios, duration_seconds, config) -> None:
    scenario_idx = 0
    current = scenarios[0]
    scenario_start = time.time()
    start_time = scenario_start
    tick_interval = config.publish_interval_seconds

    try:
        current["on_enter"]()
        print_scenario_banner(current)

        while True:
            loop_start = time.time()
            if duration_seconds and (loop_start - start_time) >= duration_seconds:
                break

            world.tick()
            publish_tick(devices)
            print_snapshot(world)

            if loop_start - scenario_start >= current["duration"]:
                scenario_idx = (scenario_idx + 1) % len(scenarios)
                current = scenarios[scenario_idx]
                scenario_start = time.time()
                current["on_enter"]()
                print_scenario_banner(current)

            elapsed = time.time() - loop_start
            time.sleep(max(0.0, tick_interval - elapsed))
    except KeyboardInterrupt:
        print("\n[NEXUS SIM] Stopping (Ctrl+C)...")
    finally:
        for device in devices:
            if device.mqtt_publisher:
                device.mqtt_publisher.close()
        print("[NEXUS SIM] All simulated devices published offline status. Grand Simulation stopped.")


# ---------------------------------------------------------------------------

def parse_args():
    parser = argparse.ArgumentParser(
        description="NEXUS Grand Simulation - end-to-end physical-intelligence demonstration."
    )
    parser.add_argument("--duration", type=int, default=None,
                         help="Run for N seconds then stop (default: cycle scenarios until Ctrl+C).")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    print("=" * 72)
    print("NEXUS GRAND SIMULATION - Physical Intelligence Demonstration")
    print("=" * 72)

    check_backend()

    print("[NEXUS SIM] Provisioning devices (idempotent)...")
    device_ids = provision_devices()

    print("[NEXUS SIM] Provisioning demonstration rules (idempotent)...")
    ensure_rules(device_ids)

    print("[NEXUS SIM] Discovering provisioned SIMULATED devices...")
    by_name = fetch_simulated_devices(set(device_ids.keys()))

    config = SimulatorConfig()
    world, devices, actuator_id = build_devices(by_name, config)
    scenarios = build_scenarios(world, actuator_id)

    print("[NEXUS SIM] All 3 ESP32 devices connected. Starting scenario engine.")
    print("[NEXUS SIM] Press Ctrl+C to stop.")
    run_simulation(world, devices, scenarios, args.duration, config)


if __name__ == "__main__":
    main()
