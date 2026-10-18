"""Locates and loads the authoritative schemas. No second copy of any schema exists (ledger P5-002)."""

from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path


def contracts_dir() -> Path:
    override = os.environ.get("NEXUS_CONTRACTS_DIR")
    if override:
        return Path(override)
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "contracts"
        if (candidate / "versions.json").is_file():
            return candidate
    raise FileNotFoundError("contracts/versions.json not found; set NEXUS_CONTRACTS_DIR")


@lru_cache(maxsize=None)
def versions() -> dict:
    return json.loads((contracts_dir() / "versions.json").read_text(encoding="utf-8"))


@lru_cache(maxsize=None)
def schema(relative_path: str) -> dict:
    return json.loads((contracts_dir() / relative_path).read_text(encoding="utf-8"))
