"""The command freshness rule of the firmware (decision D4), shared by the SDK and checked against the simulator and the validator profile.

A command is STALE when the ABSOLUTE difference between the device clock (whole seconds, like the firmware's ``time(nullptr)``) and the
command's timestamp is greater than the window (30 seconds). A command dated in the future by more than the window is as stale as an old one.
The clock is always injected; nothing here reads the wall clock implicitly."""

from __future__ import annotations

DEFAULT_WINDOW_SECONDS = 30


def is_fresh(now: float, command_timestamp: int, window: float = DEFAULT_WINDOW_SECONDS) -> bool:
    return abs(int(now) - int(command_timestamp)) <= window
