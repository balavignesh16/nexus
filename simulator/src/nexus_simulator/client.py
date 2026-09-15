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
