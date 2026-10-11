import json
import urllib.error
import urllib.request
from typing import Any, Dict, List


class NexusClient:
    """HTTP Client for communicating with the NEXUS Backend."""

    def __init__(self, backend_url: str):
        self.backend_url = backend_url

    def get_active_devices(self) -> List[Dict[str, Any]]:
        """Fetches all ACTIVE devices from the backend, filtered to
        mode=SIMULATED only.

        The backend's /devices/active endpoint applies no DeviceMode
        filtering itself (verified during the Phase 12 audit) - a REAL
        device is never returned here, enforced client-side, fail-safe
        (a device missing/without an explicit "SIMULATED" mode value is
        excluded, never assumed simulated).
        """
        url = f"{self.backend_url}/devices/active"
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=5.0) as response:
                if response.status == 200:
                    data = json.loads(response.read().decode('utf-8'))
                    return [d for d in data if d.get("mode") == "SIMULATED"]
                else:
                    print(f"Error fetching devices: HTTP {response.status}")
                    return []
        except urllib.error.URLError as e:
            print(f"Failed to connect to backend at {url}: {e}")
            return []

    def get_readback_routes(self, device_id: str) -> List[Dict[str, Any]]:
        """Only explicit enabled runtime mappings can create simulator readback routes."""
        def get(path):
            req = urllib.request.Request(f"{self.backend_url}{path}", headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=5.0) as response:
                return json.loads(response.read().decode("utf-8")) if response.status == 200 else []
        try:
            bindings = {row["id"]: row for row in get(f"/devices/{device_id}/capability-bindings")}
            mappings = get(f"/devices/{device_id}/readback-mappings")
            routes = []
            for mapping in mappings:
                if not mapping.get("enabled") or mapping.get("qualificationMode") != "COMMAND_ECHO":
                    continue
                write = bindings.get(mapping.get("writeBindingId"))
                read = bindings.get(mapping.get("readBindingId"))
                if not write or not read or not write.get("enabled") or not read.get("enabled"):
                    continue
                routes.append({
                    "writeCapability": write.get("externalIdentifiers", {}).get("commandCapability") or write["capabilityKey"].upper(),
                    "writeSubtargetKey": mapping.get("writeSubtargetKey", ""),
                    "readSensorType": read.get("externalIdentifiers", {}).get("telemetryKey") or read["capabilityKey"],
                    "readCapabilityKey": read["capabilityKey"],
                    "readUnit": read.get("unit"),
                    "selectorKey": mapping["selectorKey"],
                    "selectorPath": mapping.get("selectorPath"),
                })
            return routes
        except (urllib.error.URLError, KeyError, ValueError) as error:
            print(f"Readback routes unavailable for {device_id}: {error}")
            return []
