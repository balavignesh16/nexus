"""Standalone NEXUS device wire-contract validator (Phase 5, slice P5.1).

Validates telemetry, command, acknowledgement and status payloads against the JSON Schemas in ``contracts/``
without Spring, PostgreSQL, Mosquitto or any network access. See architecture/phase5/adapter-contract.md
sections 6 to 8 and 13.
"""

from .validator import LayerResult, Result, validate

__all__ = ["LayerResult", "Result", "validate", "__version__"]
__version__ = "1.0.0"
