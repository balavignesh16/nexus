"""ESP32-as-Device simulated device model.

A SimulatedDevice owns many capabilities (sensors and/or actuators) under
one deviceId - never a separate device per physical component. This
mirrors the locked NEXUS architecture: ESP32 = Device, attached physical
functions = DeviceCapabilities.
"""

from dataclasses import dataclass
from typing import Dict, List, Optional

from .actuators import BuzzerActuator, FanActuator, LedActuator, RelayActuator, ServoActuator
from .command_handler import CommandHandler
from .generators import (
    Bme280HumidityGenerator,
    Bme280Model,
    Bme280PressureGenerator,
    Bme280TemperatureGenerator,
    ButtonGenerator,
    GasGenerator,
    StatefulMotionGenerator,
)

# Fallback units used only when the backend-provisioned capability's own
# `unit` field is absent - the provisioned unit (from DeviceCapability) is
# always preferred when present.
DEFAULT_UNITS = {
    "TEMPERATURE": "CELSIUS",
    "HUMIDITY": "PERCENT",
    "PRESSURE": "HPA",
    "GAS": "PPM",
    "MOTION": "BOOLEAN",
    "BUTTON": "BOOLEAN",
    "LED": "BOOLEAN",
    "FAN": "BOOLEAN",
    "BUZZER": "BOOLEAN",
    "SERVO": "DEGREES",
    "RELAY": "BOOLEAN",
}

DEFAULT_RELAY_CHANNELS = [1, 2, 3, 4]


@dataclass
class ReadCapability:
    capability_type: str
    unit: str
    generator: object  # exposes .generate() -> value | None


@dataclass
class WriteCapability:
    capability_type: str
    unit: str
    actuator: object  # exposes .apply(value)


class SimulatedDevice:
    """One simulated ESP32. `capabilities` is the raw list of capability
    dicts as returned by DeviceResponse.capabilities (capabilityType,
    direction, unit, metadata) - the same shape the backend already embeds
    in GET /devices/active, so no separate capability fetch is needed."""

    def __init__(self, device_id: str, name: str, serial_number: str, capabilities: List[dict]):
        self.device_id = device_id
        self.name = name
        self.serial_number = serial_number
        self.read_capabilities: Dict[str, ReadCapability] = {}
        self.write_capabilities: Dict[str, WriteCapability] = {}
        self._bme280_model: Optional[Bme280Model] = None
        self.command_handler = CommandHandler(self)
        # Set by the owning SimulatorScheduler once the device's per-device
        # MQTT client is created - never constructed here (devices.py must
        # not depend on mqtt_publisher.py to avoid a circular import).
        self.mqtt_publisher = None

        for capability in capabilities:
            self._install_capability(capability)

    def _install_capability(self, capability: dict) -> None:
        capability_type = capability.get("capabilityType") or capability.get("type")
        direction = capability.get("direction")
        unit = capability.get("unit") or DEFAULT_UNITS.get(capability_type, "RAW")
        metadata = capability.get("metadata") or {}

        if direction == "READ":
            generator = self._build_generator(capability_type)
            if generator is None:
                return
            self.read_capabilities[capability_type] = ReadCapability(
                capability_type, unit, generator
            )
        elif direction == "WRITE":
            actuator = self._build_actuator(capability_type, metadata)
            if actuator is None:
                return
            self.write_capabilities[capability_type] = WriteCapability(
                capability_type, unit, actuator
            )

    def _get_bme280_model(self) -> Bme280Model:
        if self._bme280_model is None:
            self._bme280_model = Bme280Model()
        return self._bme280_model

    def _build_generator(self, capability_type: str):
        if capability_type == "TEMPERATURE":
            return Bme280TemperatureGenerator(self._get_bme280_model())
        if capability_type == "HUMIDITY":
            return Bme280HumidityGenerator(self._get_bme280_model())
        if capability_type == "PRESSURE":
            return Bme280PressureGenerator(self._get_bme280_model())
        if capability_type == "GAS":
            return GasGenerator()
        if capability_type == "MOTION":
            return StatefulMotionGenerator()
        if capability_type == "BUTTON":
            return ButtonGenerator()
        return None

    def _build_actuator(self, capability_type: str, metadata: dict):
        if capability_type == "LED":
            return LedActuator()
        if capability_type == "FAN":
            return FanActuator()
        if capability_type == "BUZZER":
            return BuzzerActuator()
        if capability_type == "SERVO":
            return ServoActuator()
        if capability_type == "RELAY":
            channels = metadata.get("channels") or DEFAULT_RELAY_CHANNELS
            return RelayActuator(channels)
        return None

    def has_capabilities(self) -> bool:
        return bool(self.read_capabilities or self.write_capabilities)
