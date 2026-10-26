"""NEXUS device SDK (Phase 5, slice P5.5): a small, dependency-light client for devices and simulators.

It builds contract 1.0 messages (``contractVersion`` and a stable ``messageId``), validates them locally with the standalone ``nexus_contract``
validator before anything is sent, applies the firmware's 30-second absolute command freshness rule, and sends on MQTT or HTTP. It is a client
library, not an authority: the backend still validates everything it receives."""

from .client import CONTRACT_VERSION, DeviceClient, Message
from .commands import CommandProcessor, CommandResult
from .errors import ContractViolation, TransportError
from .freshness import DEFAULT_WINDOW_SECONDS, is_fresh
from .transport import HttpTransport, InMemoryTransport, MqttTransport, Transport

__all__ = ["CONTRACT_VERSION", "CommandProcessor", "CommandResult", "ContractViolation", "DEFAULT_WINDOW_SECONDS", "DeviceClient", "HttpTransport",
           "InMemoryTransport", "Message", "MqttTransport", "Transport", "TransportError", "is_fresh"]
__version__ = "1.0.0"
