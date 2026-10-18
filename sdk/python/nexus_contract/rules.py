"""Rules a JSON Schema cannot hold (the ``x-nexus-rules`` annotations): parsing, identity, freshness, the plain-text
status preprocessing, the telemetry rejection-code mapping and the capability semantics. Implemented once here."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from decimal import Decimal

UUID_TEXT = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
MESSAGE_ID = re.compile(r"[A-Za-z0-9._:-]{1,64}")


class NotJson(Exception):
    pass


def _reject_constant(name: str):
    raise NotJson(name)


def _no_duplicate_keys(pairs):
    """A document with a repeated member name is ambiguous (RFC 8259 section 4): rejected, never last-wins."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise NotJson("duplicate member name")
        result[key] = value
    return result


def parse_json(payload: bytes):
    """Strict JSON parse: UTF-8, no NaN/Infinity, no trailing content, and non-integral numbers (1.0, 1e2) are kept
    as Decimal so they are never mistaken for integers (the firmware's ``is<long>()`` and Jackson's
    ``isIntegralNumber`` agree on that)."""
    try:
        text = payload.decode("utf-8")
        return json.loads(text, parse_float=Decimal, parse_constant=_reject_constant, object_pairs_hook=_no_duplicate_keys)
    except (UnicodeDecodeError, ValueError, NotJson, RecursionError) as error:
        raise NotJson(type(error).__name__) from error


def status_text(payload: bytes) -> str:
    """The backend's preprocessing: UTF-8 decode (invalid bytes become U+FFFD, never an error) then String.trim(),
    which strips every character up to and including U+0020 from both ends."""
    text = payload.decode("utf-8", errors="replace")
    start, end = 0, len(text)
    while start < end and text[start] <= " ":
        start += 1
    while end > start and text[end - 1] <= " ":
        end -= 1
    return text[start:end]


def is_blank(value: object) -> bool:
    return not isinstance(value, str) or value.strip() == ""


def canonical_uuid(text: str) -> str:
    return text.lower()


def is_uuid(value: object) -> bool:
    return isinstance(value, str) and UUID_TEXT.fullmatch(value) is not None


# ---------------------------------------------------------------- telemetry code mapping

def classify_telemetry(data: dict, schema_failed: bool) -> str | None:
    """Maps a structurally invalid telemetry object to the fixed code the backend listener reports, in the same
    order: Jackson mapping failure (MALFORMED_JSON, or INVALID_MESSAGE_ID for messageId), Bean Validation
    (INVALID_MESSAGE_ID, then INVALID_FIELDS), the legacy-shape check (INVALID_LEGACY_PAYLOAD). Returns None when the
    object is valid."""
    mapping_failure = False
    for field in ("deviceId", "readbackCommandId"):
        value = data.get(field)
        if not _jackson_null(value) and not is_uuid(value):
            mapping_failure = True
    timestamp = data.get("timestamp")
    if not _jackson_null(timestamp) and not _is_instant(timestamp):
        mapping_failure = True
    for field in ("sensorType", "unit", "capabilityKey"):
        if isinstance(data.get(field), (dict, list)):
            mapping_failure = True
    if mapping_failure:
        return "MALFORMED_JSON"
    message_id = data.get("messageId")
    if message_id is not None and not isinstance(message_id, str):
        return "INVALID_MESSAGE_ID"
    if isinstance(message_id, str) and not MESSAGE_ID.fullmatch(message_id):
        return "INVALID_MESSAGE_ID"
    if _jackson_null(data.get("deviceId")) or _jackson_null(data.get("timestamp")) or data.get("value") is None:
        return "INVALID_FIELDS"
    generic = isinstance(data.get("capabilityKey"), str) and data["capabilityKey"].strip() != ""
    if not generic:
        value = data.get("value")
        numeric = isinstance(value, (int, Decimal)) and not isinstance(value, bool)
        if is_blank(data.get("sensorType")) or is_blank(data.get("unit")) or not numeric:
            return "INVALID_LEGACY_PAYLOAD"
    return "INVALID_FIELDS" if schema_failed else None


def _jackson_null(value: object) -> bool:
    """Jackson maps JSON null and, for UUID and Instant members, the empty string to null; Bean Validation then sees null."""
    return value is None or value == ""


_INSTANT = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")


def _is_instant(value: object) -> bool:
    if not isinstance(value, str) or not _INSTANT.fullmatch(value):
        return False
    try:
        datetime.strptime(value[:19], "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return False
    return True


# ---------------------------------------------------------------- identity and freshness

def topic_device_matches(topic_device: str, payload_device: str) -> bool:
    """TelemetryMQTTListener compares the raw topic segment with ``UUID.toString()`` (lower case)."""
    return topic_device == canonical_uuid(payload_device)


def parse_reference_time(text: str) -> int:
    """Epoch seconds, or an ISO-8601 instant ending in Z."""
    if re.fullmatch(r"-?[0-9]{1,12}", text):
        return int(text)
    parsed = datetime.strptime(text.replace("Z", "+0000"), "%Y-%m-%dT%H:%M:%S%z")
    return int(parsed.astimezone(timezone.utc).timestamp())


def command_age_exceeds(reference_epoch: int, command_epoch: int, window_seconds: int) -> bool:
    """The firmware rule: the absolute difference, so a timestamp up to ``window`` seconds in the future is fresh."""
    return abs(reference_epoch - command_epoch) > window_seconds


# ---------------------------------------------------------------- capability semantics (layer 5)

def _whole_number(value: object) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)):
        return False
    return value == value.to_integral_value() if isinstance(value, Decimal) else True


def capability_semantics(command: dict, capability_profile: dict) -> tuple[str, str | None, str]:
    """Mirrors CommandValueValidator for the built-in capability types. Returns (status, code, detail)."""
    ctype = str(capability_profile.get("type", "")).upper()
    if command.get("command") != "SET_STATE":
        return "FAIL", "UNSUPPORTED", "command must be SET_STATE"
    value = command.get("value")
    if ctype in ("LED", "FAN", "BUZZER"):
        ok = isinstance(value, bool)
        return ("PASS", None, ctype) if ok else ("FAIL", "MALFORMED", f"{ctype} value must be a boolean")
    if ctype == "SERVO":
        if not _whole_number(value):
            return "FAIL", "MALFORMED", "SERVO value must be an integer"
        return ("PASS", None, "SERVO") if 0 <= int(value) <= 180 else ("FAIL", "MALFORMED", "SERVO value must be 0..180")
    if ctype == "RELAY":
        if not isinstance(value, dict):
            return "FAIL", "MALFORMED", "RELAY value must be an object"
        if not _whole_number(value.get("channel")):
            return "FAIL", "MALFORMED", "RELAY channel must be an integer"
        if not isinstance(value.get("state"), bool):
            return "FAIL", "MALFORMED", "RELAY state must be a boolean"
        channels = capability_profile.get("channels")
        if not isinstance(channels, list):
            return "NOT_PERFORMED", None, "channel membership needs the binding's channels"
        return ("PASS", None, "RELAY") if int(value["channel"]) in channels else ("FAIL", "MALFORMED", "RELAY channel not configured")
    return "NOT_PERFORMED", None, f"no built-in semantics for capability type {ctype or 'unknown'}"
