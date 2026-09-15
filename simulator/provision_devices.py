"""Idempotent provisioning for Phase 12's capability-driven simulated ESP32s.

Reads simulator/config/devices.yaml, then for each configured device:
  1. derives a stable serialNumber ("SIM-{name}")
  2. looks it up against existing backend devices; reuses the UUID if found
  3. creates it (deviceType=CUSTOM, mode=SIMULATED) + activates it if not
  4. provisions any capabilities from the config that aren't already present

Running this script twice must never create duplicate devices or duplicate
capabilities - RELAY in particular must always end up as exactly one
RELAY/WRITE DeviceCapability row with metadata.channels, never four rows
(Phase 11's UNIQUE(device_id, capability_type, direction) forbids that
anyway, but idempotent provisioning must not even attempt it).

This is a separate, dedicated production provisioning tool - the existing
setup_test_devices.py / benchmark.py / rule_explosion.py / mock_backend.py
load-test utilities are untouched and remain independent of this script and
of the nexus_simulator package.
"""

import json
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    import yaml
except ImportError as exc:  # pragma: no cover - environment/dependency error, not a logic path
    raise SystemExit(
        "PyYAML is required to run provisioning (pip install pyyaml, or "
        "`pip install -e .[dev]` inside simulator/)."
    ) from exc

from pydantic import BaseModel, ValidationError

BACKEND_URL = "http://localhost:8080/api/v1"
CONFIG_PATH = Path(__file__).parent / "config" / "devices.yaml"

SITE_NAME = "NEXUS Simulator Environment"
BUILDING_NAME = "Simulator Building"
SPACE_NAME = "Simulator Space"


class CapabilityConfig(BaseModel):
    type: str
    direction: str
    unit: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None


class DeviceConfig(BaseModel):
    name: str
    capabilities: List[CapabilityConfig]


class DevicesFile(BaseModel):
    devices: List[DeviceConfig]


def _request(method: str, path: str, body: Optional[dict] = None) -> Any:
    url = f"{BACKEND_URL}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=10.0) as response:
        raw = response.read()
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))


def load_devices_config() -> DevicesFile:
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    try:
        return DevicesFile.model_validate(raw)
    except ValidationError as e:
        raise SystemExit(f"Invalid devices.yaml: {e}")


def find_or_create(items: List[dict], name: str, create_fn) -> str:
    for item in items:
        if item.get("name") == name:
            return item["id"]
    return create_fn()


def ensure_site() -> str:
    sites = _request("GET", "/sites") or []
    site_body = {"name": SITE_NAME, "description": "Phase 12 simulated ESP32 environment"}
    return find_or_create(sites, SITE_NAME, lambda: _request("POST", "/sites", site_body)["id"])


def ensure_building(site_id: str) -> str:
    buildings = _request("GET", f"/sites/{site_id}/buildings") or []
    building_body = {"name": BUILDING_NAME, "description": "Phase 12 simulator building"}
    return find_or_create(buildings, BUILDING_NAME, lambda: _request(
        "POST", f"/sites/{site_id}/buildings", building_body
    )["id"])


def ensure_space(building_id: str) -> str:
    spaces = _request("GET", f"/buildings/{building_id}/spaces") or []
    space_body = {"name": SPACE_NAME, "description": "Phase 12 simulator space"}
    return find_or_create(spaces, SPACE_NAME, lambda: _request(
        "POST", f"/buildings/{building_id}/spaces", space_body
    )["id"])


def stable_serial_number(device_name: str) -> str:
    return f"SIM-{device_name}"


def ensure_device(
    space_id: str, existing_by_serial: Dict[str, dict], device_config: DeviceConfig
) -> dict:
    serial_number = stable_serial_number(device_config.name)
    existing = existing_by_serial.get(serial_number)
    if existing is not None:
        print(f"[{device_config.name}] already provisioned as {existing['id']} "
              f"(serialNumber={serial_number}) - reusing")
        return existing

    print(f"[{device_config.name}] creating device (serialNumber={serial_number})")
    created = _request("POST", f"/spaces/{space_id}/devices", {
        "name": device_config.name,
        "deviceType": "CUSTOM",
        "manufacturer": "NEXUS Simulator",
        "model": "ESP32-DevKitV1-SIM",
        "serialNumber": serial_number,
        "description": "Phase 12 capability-driven simulated ESP32",
        "mode": "SIMULATED",
    })

    _request("PUT", f"/devices/{created['id']}", {
        "name": device_config.name,
        "status": "ACTIVE",
        "description": "Phase 12 capability-driven simulated ESP32",
    })

    return created


def ensure_capabilities(device_id: str, device_config: DeviceConfig) -> None:
    existing = _request("GET", f"/devices/{device_id}/capabilities") or []
    existing_types = {c["capabilityType"] for c in existing}

    for capability in device_config.capabilities:
        if capability.type in existing_types:
            print(f"  capability {capability.type} already present - skipping")
            continue

        print(f"  provisioning capability {capability.type}/{capability.direction}")
        _request("POST", f"/devices/{device_id}/capabilities", {
            "capabilityType": capability.type,
            "direction": capability.direction,
            "unit": capability.unit,
            "metadata": capability.metadata,
        })


def main() -> None:
    config = load_devices_config()

    site_id = ensure_site()
    building_id = ensure_building(site_id)
    space_id = ensure_space(building_id)

    all_devices = _request("GET", "/devices") or []
    existing_by_serial = {d["serialNumber"]: d for d in all_devices if d.get("serialNumber")}

    for device_config in config.devices:
        device = ensure_device(space_id, existing_by_serial, device_config)
        ensure_capabilities(device["id"], device_config)

    print("Provisioning complete.")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as e:
        raise SystemExit(f"Failed to reach backend at {BACKEND_URL}: {e}")
