"""Simulated actuator state models for the WRITE-direction BOM capabilities.

An actuator's `state`/`angle`/`channels` here is the simulated device's own
internal belief about itself after accepting a command - it is explicitly
NOT a claim about any physical outcome (no real LED, fan, buzzer, servo, or
relay exists behind this code).
"""


class ActuatorValidationError(Exception):
    """Raised by an actuator's apply() when a command value fails validation.

    `reason` is one of the approved ACK reason vocabulary: MALFORMED.
    (Actuators only ever raise MALFORMED - UNSUPPORTED/STALE are decided
    earlier in the command handler pipeline, before an actuator is reached.)
    """

    def __init__(self, reason: str = "MALFORMED"):
        self.reason = reason
        super().__init__(reason)


class BooleanActuator:
    """Shared ON/OFF state model for LED, FAN, and BUZZER."""

    def __init__(self, initial: bool = False):
        self.state = initial

    def apply(self, value) -> None:
        if not isinstance(value, bool):
            raise ActuatorValidationError("MALFORMED")
        self.state = value


class LedActuator(BooleanActuator):
    pass


class FanActuator(BooleanActuator):
    pass


class BuzzerActuator(BooleanActuator):
    pass


class ServoActuator:
    """SG90-style servo: integer angle in the valid 0-180 degree range."""

    def __init__(self, initial_angle: int = 0):
        self.angle = initial_angle

    def apply(self, value) -> None:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ActuatorValidationError("MALFORMED")
        if not (0 <= value <= 180):
            raise ActuatorValidationError("MALFORMED")
        self.angle = value


class RelayActuator:
    """One 4-channel relay module represented as a single actuator instance
    (matching the single RELAY/WRITE DeviceCapability row + metadata.channels
    model) - never as separate per-channel actuator objects."""

    def __init__(self, channels):
        self.channels = {int(ch): False for ch in channels}

    def apply(self, value) -> None:
        if not isinstance(value, dict):
            raise ActuatorValidationError("MALFORMED")

        channel = value.get("channel")
        state = value.get("state")

        if isinstance(channel, bool) or not isinstance(channel, int):
            raise ActuatorValidationError("MALFORMED")
        if not isinstance(state, bool):
            raise ActuatorValidationError("MALFORMED")
        if channel not in self.channels:
            raise ActuatorValidationError("MALFORMED")

        self.channels[channel] = state
