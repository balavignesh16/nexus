"""Layered validation of one device wire message (adapter-contract.md section 8.2).

Layers: ``version`` (resolution and ``requires``), ``structural`` (JSON Schema), ``profile`` (a legacy device
profile), ``identity`` (topic versus payload), ``freshness`` (an injected reference time) and ``capability``
(capability-specific semantics). A layer that lacks the runtime input it needs is NOT_PERFORMED and is never
reported as a pass; nothing here reads the wall clock.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal

from jsonschema import Draft202012Validator

from . import loader, rules, versions

KINDS = ("telemetry", "command", "acknowledgement", "status")
PASS, FAIL, NOT_PERFORMED, NOT_APPLICABLE = "PASS", "FAIL", "NOT_PERFORMED", "NOT_APPLICABLE"
LAYERS = ("version", "structural", "profile", "identity", "freshness", "capability")

_validators: dict[str, Draft202012Validator] = {}


@dataclass
class LayerResult:
    name: str
    status: str
    code: str | None = None
    detail: str | None = None

    def as_dict(self) -> dict:
        return {"layer": self.name, "status": self.status, "code": self.code, "detail": self.detail}


@dataclass
class Result:
    kind: str
    version: str
    layers: list[LayerResult] = field(default_factory=list)
    unknown_fields: int = 0
    forward_minor: bool = False

    @property
    def verdict(self) -> str:
        return "REJECT" if any(layer.status == FAIL for layer in self.layers) else "ACCEPT"

    @property
    def code(self) -> str | None:
        for layer in self.layers:
            if layer.status == FAIL:
                return layer.code
        return None

    @property
    def not_performed(self) -> list[str]:
        return [layer.name for layer in self.layers if layer.status == NOT_PERFORMED]

    def layer(self, name: str) -> LayerResult:
        return next(layer for layer in self.layers if layer.name == name)

    def as_dict(self) -> dict:
        return {"kind": self.kind, "version": self.version, "verdict": self.verdict, "code": self.code,
                "forwardMinor": self.forward_minor, "unknownFields": self.unknown_fields,
                "layers": [layer.as_dict() for layer in self.layers]}


def _validator(relative_path: str) -> Draft202012Validator:
    if relative_path not in _validators:
        document = loader.schema(relative_path)
        Draft202012Validator.check_schema(document)
        _validators[relative_path] = Draft202012Validator(document)
    return _validators[relative_path]


def _skipped(name: str, why: str) -> LayerResult:
    """The layer needed a runtime input that was not supplied (or an earlier layer failed)."""
    return LayerResult(name, NOT_PERFORMED, None, why)


def _na(name: str, why: str) -> LayerResult:
    """The layer does not apply to this message kind or was not requested; it neither passes nor is missing."""
    return LayerResult(name, NOT_APPLICABLE, None, why)


def validate(kind: str, payload: bytes, *, legacy: bool = False, declared_version: str | None = None,
             profile: str | None = None, topic_device_id: str | None = None, reference_time: int | None = None,
             freshness_window: int | None = None, capability_profile: dict | None = None) -> Result:
    """Validate ``payload`` (raw bytes exactly as received) as ``kind``.

    ``legacy`` applies the legacy-v1 policy (contractVersion and requires are treated as unknown fields, as the
    committed code treats them). ``declared_version`` is a version declared outside the body (the HTTP header).
    """
    if kind not in KINDS:
        raise ValueError(f"unknown kind {kind!r}")
    table = loader.versions()
    result = Result(kind=kind, version=versions.LEGACY)
    schema_path = table["kinds"][kind]

    if kind == "status":
        return _validate_status(result, payload, schema_path, declared_version)

    try:
        data = rules.parse_json(payload)
    except rules.NotJson:
        result.layers.append(_skipped("version", "not resolvable before the payload parses"))
        result.layers.append(LayerResult("structural", FAIL, "MALFORMED_JSON"))
        return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, None)
    if not isinstance(data, dict):
        result.layers.append(_skipped("version", "not resolvable before the payload parses"))
        result.layers.append(LayerResult("structural", FAIL, "MALFORMED_JSON" if kind == "telemetry" else "INVALID_FIELDS"))
        return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, None)

    # --- version layer
    if legacy:
        resolution = versions.Resolution("legacy-v1", versions.LEGACY)
    else:
        resolution = versions.resolve(data.get("contractVersion"), present="contractVersion" in data, header=declared_version)
    result.version = resolution.effective if resolution.effective else "unresolved"
    result.forward_minor = resolution.kind == "forward-minor"
    if resolution.kind == "rejected":
        result.layers.append(LayerResult("version", FAIL, resolution.code))
        return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, data)

    # --- structural layer (the same schema for every accepted version: a newer minor never loosens a known field)
    schema_document = loader.schema(schema_path)
    known = set(schema_document.get("properties", {}))
    if legacy:
        known -= {"contractVersion", "requires"}
    result.unknown_fields = sum(1 for key in data if key not in known)
    errors = list(_validator(schema_path).iter_errors(data if not legacy else _without(data, ("contractVersion", "requires"))))
    if errors:
        code = rules.classify_telemetry(data, True) if kind == "telemetry" else "INVALID_FIELDS"
        result.layers.append(LayerResult("version", PASS))
        result.layers.append(LayerResult("structural", FAIL, code or "INVALID_FIELDS"))
        return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, data)
    if kind == "telemetry":
        # Schema-valid telemetry can still be a code the backend gives; defensive consistency check.
        code = rules.classify_telemetry(data, False)
        if code is not None:
            result.layers.append(LayerResult("version", PASS))
            result.layers.append(LayerResult("structural", FAIL, code))
            return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, data)
    requires = [] if legacy else data.get("requires")
    if requires:
        result.layers.append(LayerResult("version", FAIL, "UNSUPPORTED_REQUIRED_FEATURE"))
        result.layers.append(LayerResult("structural", PASS))
        return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, data, done=("version", "structural"))
    result.layers.append(LayerResult("version", PASS, None, resolution.kind))
    result.layers.append(LayerResult("structural", PASS))
    return _pad(result, kind, profile, topic_device_id, reference_time, freshness_window, capability_profile, data, done=("version", "structural"))


def _without(data: dict, keys) -> dict:
    return {k: v for k, v in data.items() if k not in keys}


def _validate_status(result: Result, payload: bytes, schema_path: str, declared_version: str | None) -> Result:
    # The status payload has no body for a version. A header version (HTTP only) is still resolved.
    if declared_version is not None:
        resolution = versions.resolve(None, present=False, header=declared_version)
        if resolution.kind == "rejected":
            result.layers.append(LayerResult("version", FAIL, resolution.code))
            return result
        result.version = resolution.effective
    result.layers.append(LayerResult("version", PASS, None, "status has no body version"))
    text = rules.status_text(payload)
    if any(True for _ in _validator(schema_path).iter_errors(text)):
        result.layers.append(LayerResult("structural", FAIL, "INVALID_FIELDS"))
    else:
        result.layers.append(LayerResult("structural", PASS))
    result.layers.append(_na("profile", "not applicable to status"))
    result.layers.append(_na("identity", "not applicable to status"))
    result.layers.append(_na("freshness", "not applicable to status"))
    result.layers.append(_na("capability", "not applicable to status"))
    return result


def _pad(result: Result, kind: str, profile, topic_device_id, reference_time, freshness_window, capability_profile,
         data, done: tuple = ()) -> Result:
    """Appends the remaining layers. After a failed earlier layer the later ones are NOT_PERFORMED because there is
    no trustworthy parsed message to judge; on success they run when their input is present."""
    ok = result.verdict == "ACCEPT" and "structural" in done
    names = {layer.name for layer in result.layers}
    for name in LAYERS:
        if name in names:
            continue
        if not ok:
            result.layers.append(_skipped(name, "an earlier layer failed"))
        elif name == "profile":
            result.layers.append(_profile_layer(kind, profile, data))
        elif name == "identity":
            result.layers.append(_identity_layer(kind, topic_device_id, data))
        elif name == "freshness":
            result.layers.append(_freshness_layer(kind, profile, data, reference_time, freshness_window))
        elif name == "capability":
            result.layers.append(_capability_layer(kind, data, capability_profile))
    return result


def _profile_layer(kind: str, profile: str | None, data: dict) -> LayerResult:
    if profile is None:
        return _na("profile", "no profile requested")
    entry = loader.versions()["profiles"].get(profile)
    if entry is None or kind not in entry:
        return _na("profile", f"profile {profile!r} has no {kind} rules")
    errors = list(_validator(entry[kind]).iter_errors(data))
    if errors:
        return LayerResult("profile", FAIL, "MALFORMED", profile)
    if data.get("capability") != entry["capability"] or data.get("command") != entry["supportedCommand"]:
        return LayerResult("profile", FAIL, "UNSUPPORTED", profile)
    return LayerResult("profile", PASS, None, profile)


def _identity_layer(kind: str, topic_device_id: str | None, data: dict) -> LayerResult:
    if kind != "telemetry":
        return _na("identity", "no payload device id to compare for this kind")
    if topic_device_id is None:
        return _skipped("identity", "no topic supplied")
    if rules.topic_device_matches(topic_device_id, data["deviceId"]):
        return LayerResult("identity", PASS)
    return LayerResult("identity", FAIL, "TOPIC_DEVICE_MISMATCH")


def _freshness_layer(kind: str, profile: str | None, data: dict, reference_time: int | None, window: int | None) -> LayerResult:
    if kind != "command":
        return _na("freshness", "freshness applies to commands")
    if reference_time is None:
        return _skipped("freshness", "no reference time injected")
    if window is None and profile is not None:
        entry = loader.versions()["profiles"].get(profile)
        window = entry["commandFreshnessWindowSeconds"] if entry else None
    if window is None:
        return _skipped("freshness", "no freshness window defined")
    timestamp = data.get("timestamp")
    if isinstance(timestamp, Decimal) or not isinstance(timestamp, int):
        return _skipped("freshness", "timestamp is not an integer")
    if rules.command_age_exceeds(reference_time, timestamp, window):
        return LayerResult("freshness", FAIL, "STALE", f"window {window}s, absolute")
    return LayerResult("freshness", PASS, None, f"window {window}s, absolute")


def _capability_layer(kind: str, data: dict, capability_profile: dict | None) -> LayerResult:
    if kind != "command":
        return _na("capability", "capability semantics apply to commands")
    if capability_profile is None:
        return _skipped("capability", "no capability profile supplied")
    status, code, detail = rules.capability_semantics(data, capability_profile)
    return LayerResult("capability", status, code, detail)
