"""Transports: how a device client puts a message on the wire. ``InMemoryTransport`` records messages (tests), ``MqttTransport`` wraps a
paho-style client (QoS 1), ``HttpTransport`` posts to the NEXUS HTTP adapter with the device's own credential."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import List, Protocol

from .errors import TransportError


class Transport(Protocol):
    def send(self, kind: str, topic: str, payload: bytes, retain: bool = False) -> None:
        """``kind`` is telemetry, acknowledgement or status; ``topic`` is the MQTT topic (HTTP derives its path from ``kind``)."""


@dataclass
class Sent:
    kind: str
    topic: str
    payload: bytes
    retain: bool


@dataclass
class InMemoryTransport:
    sent: List[Sent] = field(default_factory=list)

    def send(self, kind: str, topic: str, payload: bytes, retain: bool = False) -> None:
        self.sent.append(Sent(kind, topic, bytes(payload), retain))


class MqttTransport:
    """Publishes with QoS 1 through any client that has ``publish(topic, payload, qos=..., retain=...)`` (paho-mqtt does). The paho package is
    not imported here, so the SDK works without it."""

    def __init__(self, client):
        self._client = client

    def send(self, kind: str, topic: str, payload: bytes, retain: bool = False) -> None:
        self._client.publish(topic, payload, qos=1, retain=retain)


class HttpTransport:
    """Posts to ``/api/v1/device-adapter/devices/<id>/<telemetry|acknowledgements|status>`` with ``Authorization: Bearer <key>``. The key is held
    in this object only, travels only in that header, and is never part of a URL, an exception or a repr."""

    _PATHS = {"telemetry": "telemetry", "acknowledgement": "acknowledgements", "status": "status"}

    def __init__(self, base_url: str, device_id: str, key: str, timeout: float = 10.0):
        if not base_url.startswith(("http://", "https://")) or "?" in base_url or "@" in base_url.split("//", 1)[1].split("/", 1)[0]:
            raise ValueError("base_url must be a plain http(s) origin without credentials or a query")
        self._base = base_url.rstrip("/")
        self._device_id = device_id
        self._key = key
        self._timeout = timeout

    def __repr__(self) -> str:
        return f"HttpTransport(base_url={self._base!r}, device_id={self._device_id!r})"

    def send(self, kind: str, topic: str, payload: bytes, retain: bool = False) -> None:
        path = self._PATHS[kind]
        request = urllib.request.Request(f"{self._base}/api/v1/device-adapter/devices/{self._device_id}/{path}", data=payload, method="POST")
        request.add_header("Authorization", f"Bearer {self._key}")
        request.add_header("Content-Type", "text/plain; charset=utf-8" if kind == "status" else "application/json")
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                response.read()
        except urllib.error.HTTPError as error:
            code = None
            try:
                code = json.loads(error.read().decode("utf-8")).get("error")
            except (ValueError, UnicodeDecodeError, AttributeError):
                pass
            raise TransportError(f"the server answered {error.code} {code or ''}".strip(), status=error.code, code=code) from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise TransportError(f"the request could not be completed ({type(error).__name__})") from None
