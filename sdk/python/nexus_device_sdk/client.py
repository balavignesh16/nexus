"""The device client: builds contract 1.0 messages, validates them locally, and sends them on a transport."""

from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Optional

from nexus_contract.validator import validate

from .errors import ContractViolation
from .transport import Transport

CONTRACT_VERSION = "1.0"


@dataclass(frozen=True)
class Message:
    """One message exactly as it is (or was) sent. ``message_id`` is the telemetry deduplication identity; replaying the same Message keeps it."""

    kind: str
    topic: str
    payload: bytes
    message_id: Optional[str] = None
    retain: bool = False


class DeviceClient:
    """Publishes telemetry, status and acknowledgements for ONE device.

    * Every telemetry message carries ``contractVersion`` 1.0 and a UUID ``messageId``; the id is chosen once per sample, so a replay of the same
      Message is an identical redelivery (QoS 1 semantics) that the backend's inbox stores once.
    * Every message is validated before anything is sent, with the same validator and the same fixed codes as ``python -m nexus_contract``; an
      invalid message raises ``ContractViolation`` and nothing reaches the transport.
    * The clock and the id source are injected for deterministic tests; the defaults are the wall clock and ``uuid.uuid4``.
    """

    def __init__(self, device_id: str, transport: Transport, *, clock: Callable[[], float] = time.time,
                 new_id: Callable[[], uuid.UUID] = uuid.uuid4, validate_before_send: bool = True):
        self.device_id = str(uuid.UUID(device_id))
        self._transport = transport
        self._clock = clock
        self._new_id = new_id
        self._validate = validate_before_send
        self.last: Optional[Message] = None

    # ---- topics (adapter-contract.md section 7)

    @property
    def telemetry_topic(self) -> str:
        return f"nexus/devices/{self.device_id}/telemetry"

    @property
    def status_topic(self) -> str:
        return f"nexus/devices/{self.device_id}/status"

    @property
    def ack_topic(self) -> str:
        return f"nexus/devices/{self.device_id}/commands/ack"

    # ---- building

    def now_iso(self) -> str:
        """Seconds precision with a Z suffix, the form the committed firmware sends."""
        return datetime.fromtimestamp(int(self._clock()), tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def build_telemetry(self, sensor_type: str, value, unit: str, *, capability_key: Optional[str] = None,
                        readback_command_id: Optional[str] = None, message_id: Optional[str] = None) -> Message:
        identity = message_id if message_id is not None else str(self._new_id())
        body = {"contractVersion": CONTRACT_VERSION, "deviceId": self.device_id, "timestamp": self.now_iso(), "sensorType": sensor_type,
                "value": value, "unit": unit, "messageId": identity}
        if capability_key is not None:
            body["capabilityKey"] = capability_key
        if readback_command_id is not None:
            body["readbackCommandId"] = readback_command_id
        return Message("telemetry", self.telemetry_topic, _encode(body), identity)

    def build_acknowledgement(self, command_id: str, status: str, reason: Optional[str] = None) -> Message:
        body = {"contractVersion": CONTRACT_VERSION, "commandId": command_id, "status": status, "timestamp": int(self._clock())}
        if reason is not None:
            body["reason"] = reason
        return Message("acknowledgement", self.ack_topic, _encode(body))

    def build_status(self, online: bool) -> Message:
        return Message("status", self.status_topic, b"online" if online else b"offline", retain=True)

    # ---- sending

    def send(self, message: Message) -> Message:
        """Validates (unless disabled) and sends. The message is remembered for ``redeliver_last``."""
        if self._validate:
            self.check(message)
        self._transport.send(message.kind, message.topic, message.payload, message.retain)
        self.last = message
        return message

    def publish_telemetry(self, sensor_type: str, value, unit: str, **options) -> Message:
        return self.send(self.build_telemetry(sensor_type, value, unit, **options))

    def publish_acknowledgement(self, command_id: str, status: str, reason: Optional[str] = None) -> Message:
        return self.send(self.build_acknowledgement(command_id, status, reason))

    def publish_status(self, online: bool) -> Message:
        return self.send(self.build_status(online))

    def redeliver(self, message: Message) -> Message:
        """The duplicate-redelivery hook: sends the IDENTICAL bytes again, the way a QoS 1 broker redelivers an unacknowledged message. The
        telemetry ``messageId`` is therefore the same, and the backend stores the sample once."""
        self._transport.send(message.kind, message.topic, message.payload, message.retain)
        return message

    def redeliver_last(self) -> Message:
        if self.last is None:
            raise ValueError("nothing has been sent yet")
        return self.redeliver(self.last)

    # ---- local validation

    def check(self, message: Message) -> None:
        """Raises ``ContractViolation`` with the validator's fixed code when the message would be refused; sends nothing."""
        result = validate(message.kind, message.payload, topic_device_id=self.device_id if message.kind == "telemetry" else None)
        if result.verdict == "REJECT":
            failing = next(layer for layer in result.layers if layer.status == "FAIL")
            raise ContractViolation(failing.code or "INVALID_FIELDS", failing.name)


def _encode(body: dict) -> bytes:
    return json.dumps(body, separators=(",", ":"), allow_nan=False).encode("utf-8")
