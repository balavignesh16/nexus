"""Errors of the device SDK. Messages carry fixed codes only: never payload text, never a key."""

from __future__ import annotations


class ContractViolation(Exception):
    """A message was refused LOCALLY, before anything was sent, with the fixed code the standalone validator reports.

    ``layer`` is the validator layer that failed (version, structural, identity ...)."""

    def __init__(self, code: str, layer: str):
        self.code = code
        self.layer = layer
        super().__init__(f"{layer}: {code}")


class TransportError(Exception):
    """A transport could not deliver a message. ``status`` is the HTTP status when there is one and ``code`` the fixed error code the server
    answered (for example UNAUTHENTICATED); the credential is never part of the text."""

    def __init__(self, message: str, status: int | None = None, code: str | None = None):
        self.status = status
        self.code = code
        super().__init__(message)
