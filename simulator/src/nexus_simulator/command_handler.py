"""Command parsing/validation/ACK generation for a simulated device.

Implements the approved pipeline:

    JSON parse -> required fields -> capability exists -> direction WRITE
        -> command supported -> value valid -> timestamp fresh
        -> duplicate commandId check -> mutate actuator state -> ACK

ACCEPTED means the simulated device parsed, validated, and applied the
command to its own simulated actuator state. It is never a claim about a
physical outcome. REJECTED always carries one of the approved reasons:
MALFORMED, UNSUPPORTED, STALE.
"""

import json
import time
from collections import OrderedDict

from .actuators import ActuatorValidationError

SUPPORTED_COMMAND = "SET_STATE"
DEFAULT_STALE_WINDOW_SECONDS = 300.0
DEFAULT_MAX_COMMAND_HISTORY = 500


class CommandHandler:
    """Owns one simulated device's actuator command pipeline and its bounded
    commandId de-duplication history."""

    def __init__(self, device, stale_window_seconds: float = DEFAULT_STALE_WINDOW_SECONDS,
                 max_history: int = DEFAULT_MAX_COMMAND_HISTORY, clock=time.time):
        self.device = device
        self.stale_window_seconds = stale_window_seconds
        self.max_history = max_history
        self._clock = clock
        # commandId -> (status, reason) - bounded LRU so a long-running
        # device doesn't grow this unboundedly.
        self._applied: "OrderedDict[str, tuple]" = OrderedDict()

    def handle(self, payload_bytes: bytes):
        """Returns (commandId_or_None, status, reason_or_None).

        commandId is None only when the payload is malformed beyond the
        point of even extracting a commandId to correlate an ACK against -
        callers must not publish an ACK in that case (there is nothing to
        correlate it to).
        """
        try:
            data = json.loads(payload_bytes.decode("utf-8"))
        except Exception:
            return None, "REJECTED", "MALFORMED"

        if not isinstance(data, dict):
            return None, "REJECTED", "MALFORMED"

        command_id = data.get("commandId")
        capability = data.get("capability")
        command = data.get("command")
        value = data.get("value")
        timestamp = data.get("timestamp")

        if not command_id or not isinstance(command_id, str):
            return None, "REJECTED", "MALFORMED"

        if not capability or not command or value is None or timestamp is None:
            return self._finish(command_id, "REJECTED", "MALFORMED")

        if command_id in self._applied:
            status, reason = self._applied[command_id]
            return command_id, status, reason

        write_capability = self.device.write_capabilities.get(capability)
        if write_capability is None:
            return self._finish(command_id, "REJECTED", "UNSUPPORTED")

        if command != SUPPORTED_COMMAND:
            return self._finish(command_id, "REJECTED", "UNSUPPORTED")

        try:
            timestamp_value = float(timestamp)
        except (TypeError, ValueError):
            return self._finish(command_id, "REJECTED", "MALFORMED")

        if self._clock() - timestamp_value > self.stale_window_seconds:
            return self._finish(command_id, "REJECTED", "STALE")

        try:
            write_capability.actuator.apply(value)
        except ActuatorValidationError as e:
            return self._finish(command_id, "REJECTED", e.reason)

        return self._finish(command_id, "ACCEPTED", None)

    def _finish(self, command_id: str, status: str, reason):
        self._applied[command_id] = (status, reason)
        if len(self._applied) > self.max_history:
            self._applied.popitem(last=False)
        return command_id, status, reason
