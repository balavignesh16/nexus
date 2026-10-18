"""Contract version resolution: a total function (adapter-contract.md section 6, ledger P5-015 to P5-018)."""

from __future__ import annotations

import re
from dataclasses import dataclass

from . import loader

_VERSION = re.compile(r"(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})")

LEGACY = "legacy-v1"


@dataclass(frozen=True)
class Resolution:
    """``kind`` is legacy-v1, exact, forward-minor or rejected. ``effective`` is the schema version used."""

    kind: str
    effective: str | None
    code: str | None = None


def resolve(body_value: object = None, *, present: bool = False, header: str | None = None) -> Resolution:
    """Resolve the version of one message.

    ``present`` says whether the body carried a contractVersion member at all, so an explicit null or empty
    string is rejected while an absent member is legacy-v1. ``header`` is the HTTP header value, if any; when
    both are given and differ the message is rejected CONTRACT_VERSION_CONFLICT.
    """
    if header is not None and present and body_value != header:
        return Resolution("rejected", None, "CONTRACT_VERSION_CONFLICT")
    value = header if header is not None else body_value
    if header is None and not present:
        return Resolution("legacy-v1", LEGACY)
    if not isinstance(value, str) or not _VERSION.fullmatch(value):
        return Resolution("rejected", None, "INVALID_CONTRACT_VERSION")
    major, minor = (int(part) for part in value.split("."))
    table = loader.versions()
    if major not in table["supportedMajors"]:
        return Resolution("rejected", None, "UNSUPPORTED_CONTRACT_MAJOR")
    highest = table["highestKnownMinor"][str(major)]
    if minor > highest:
        return Resolution("forward-minor", f"{major}.{highest}")
    return Resolution("exact", f"{major}.{minor}")
