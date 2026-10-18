"""Command handling for a device: validate, apply the firmware's freshness rule, apply the command, build the acknowledgement."""

from __future__ import annotations

import time
from collections import OrderedDict
from typing import Any, Callable, Dict, Mapping, NamedTuple, Optional

from nexus_contract import rules

from .freshness import DEFAULT_WINDOW_SECONDS, is_fresh

SUPPORTED_COMMAND = "SET_STATE"


class CommandResult(NamedTuple):
    """``command_id`` is None when the message is not even a command with a usable id: there is nothing to correlate an acknowledgement with, so
    none is produced (like the firmware). Otherwise ``status`` is ACCEPTED or REJECTED and ``reason`` one of MALFORMED, UNSUPPORTED, STALE."""

    command_id: Optional[str]
    status: Optional[str]
    reason: Optional[str]

    @property
    def acknowledges(self) -> bool:
        return self.command_id is not None


class CommandProcessor:
    """The same pipeline as the committed firmware and the simulator, in this order: JSON, required fields, supported capability and command,
    freshness (absolute difference, 30 s, whole-second device clock), apply, acknowledge. Handlers raise ``ValueError`` for a value they
    cannot apply, which is MALFORMED. A repeated commandId is answered with the first outcome and applied once (bounded history)."""

    def __init__(self, handlers: Mapping[str, Callable[[Any], None]], *, clock: Callable[[], float] = time.time,
                 window_seconds: float = DEFAULT_WINDOW_SECONDS, max_history: int = 500):
        self._handlers: Dict[str, Callable[[Any], None]] = dict(handlers)
        self._clock = clock
        self.window_seconds = window_seconds
        self._max_history = max_history
        self._applied: "OrderedDict[str, tuple]" = OrderedDict()

    def handle(self, payload: bytes) -> CommandResult:
        try:
            data = rules.parse_json(payload)
        except rules.NotJson:
            return CommandResult(None, None, None)
        if not isinstance(data, dict):
            return CommandResult(None, None, None)
        command_id = data.get("commandId")
        if not isinstance(command_id, str) or not rules.is_uuid(command_id):
            return CommandResult(None, None, None)
        capability, command, value, timestamp = data.get("capability"), data.get("command"), data.get("value"), data.get("timestamp")
        if not isinstance(capability, str) or not isinstance(command, str) or value is None or isinstance(timestamp, bool) or not isinstance(timestamp, int):
            return self._finish(command_id, "REJECTED", "MALFORMED")
        if command_id in self._applied:
            status, reason = self._applied[command_id]
            return CommandResult(command_id, status, reason)
        handler = self._handlers.get(capability)
        if handler is None or command != SUPPORTED_COMMAND:
            return self._finish(command_id, "REJECTED", "UNSUPPORTED")
        if not is_fresh(self._clock(), timestamp, self.window_seconds):
            return self._finish(command_id, "REJECTED", "STALE")
        try:
            handler(value)
        except ValueError:
            return self._finish(command_id, "REJECTED", "MALFORMED")
        return self._finish(command_id, "ACCEPTED", None)

    def _finish(self, command_id: str, status: str, reason: Optional[str]) -> CommandResult:
        self._applied[command_id] = (status, reason)
        if len(self._applied) > self._max_history:
            self._applied.popitem(last=False)
        return CommandResult(command_id, status, reason)

    def respond(self, payload: bytes, client) -> CommandResult:
        """Handles one command and sends its acknowledgement through ``client`` (a DeviceClient), if it has one."""
        result = self.handle(payload)
        if result.acknowledges:
            client.publish_acknowledgement(result.command_id, result.status, result.reason)
        return result
