import random
import time
from typing import Protocol


class TelemetryGenerator(Protocol):
    def generate(self) -> any:
        ...


class TemperatureGenerator:
    """Random walk generator for Temperature (20.0 - 35.0 °C)."""

    def __init__(self, current_value: float = 22.0):
        self.current_value = current_value
        self.min_val = 20.0
        self.max_val = 35.0
        self.max_step = 0.5

    def generate(self) -> float:
        step = random.uniform(-self.max_step, self.max_step)
        self.current_value += step

        # Clamp to bounds
        self.current_value = max(self.min_val, min(self.current_value, self.max_val))
        return round(self.current_value, 2)


class GasGenerator:
    """Random walk generator for Gas Sensor (0 - 1000 ppm)."""

    def __init__(self, current_value: int = 400):
        self.current_value = current_value
        self.min_val = 0
        self.max_val = 1000
        self.max_step = 20

    def generate(self) -> int:
        step = random.randint(-self.max_step, self.max_step)
        self.current_value += step

        # Clamp to bounds
        self.current_value = max(self.min_val, min(self.current_value, self.max_val))
        return self.current_value


class MotionGenerator:
    """Random boolean generator for Motion Sensor (mostly false).

    Kept unchanged for the legacy TEMPERATURE_SENSOR/GAS_SENSOR/MOTION_SENSOR
    device-type path. Capability-driven MOTION simulation uses
    StatefulMotionGenerator below instead.
    """

    def generate(self) -> bool:
        return random.random() > 0.8  # 20% chance of motion


class Bme280Model:
    """Shared, correlated simulated state for one BME280 sensor.

    A single instance backs a device's TEMPERATURE/HUMIDITY/PRESSURE
    capabilities together, so humidity/pressure aren't independent random
    walks unrelated to the device's own temperature - approved simulation
    ranges only, not claimed physical accuracy.
    """

    def __init__(self, temperature: float = 22.0, humidity: float = 50.0, pressure: float = 1013.0):
        self.temperature = temperature
        self.humidity = humidity
        self.pressure = pressure

    def generate_temperature(self) -> float:
        step = random.uniform(-0.5, 0.5)
        self.temperature = max(20.0, min(35.0, self.temperature + step))
        return round(self.temperature, 2)

    def generate_humidity(self) -> float:
        # Mildly inverse-correlated with temperature so the three readings
        # feel like one sensor rather than three unrelated generators.
        drift = (27.5 - self.temperature) * 0.05
        step = random.uniform(-1.0, 1.0) + drift
        self.humidity = max(30.0, min(70.0, self.humidity + step))
        return round(self.humidity, 2)

    def generate_pressure(self) -> float:
        step = random.uniform(-0.5, 0.5)
        self.pressure = max(980.0, min(1030.0, self.pressure + step))
        return round(self.pressure, 2)


class Bme280TemperatureGenerator:
    """Adapts one shared Bme280Model to the TelemetryGenerator interface."""

    def __init__(self, model: Bme280Model):
        self.model = model

    def generate(self) -> float:
        return self.model.generate_temperature()


class Bme280HumidityGenerator:
    def __init__(self, model: Bme280Model):
        self.model = model

    def generate(self) -> float:
        return self.model.generate_humidity()


class Bme280PressureGenerator:
    def __init__(self, model: Bme280Model):
        self.model = model

    def generate(self) -> float:
        return self.model.generate_pressure()


class StatefulMotionGenerator:
    """PIR-like motion model: once triggered, stays "true" for a retrigger
    hold window instead of flipping independently on every tick."""

    def __init__(self, trigger_probability: float = 0.2, hold_seconds: float = 6.0,
                 clock=time.time):
        self.trigger_probability = trigger_probability
        self.hold_seconds = hold_seconds
        self._clock = clock
        self._active_until = 0.0

    def generate(self) -> bool:
        now = self._clock()
        if now < self._active_until:
            return True
        if random.random() < self.trigger_probability:
            self._active_until = now + self.hold_seconds
            return True
        return False


class ButtonGenerator:
    """Event-driven push-button model: returns None on every tick where the
    button's simulated state does not change, and only returns a value
    (pressed=True / released=False) on an actual state transition - callers
    must treat None as "nothing to publish this tick", not a value of 0."""

    def __init__(self, press_probability: float = 0.05, random_fn=random.random):
        self.press_probability = press_probability
        self._random_fn = random_fn
        self.state = False

    def generate(self):
        if self._random_fn() < self.press_probability:
            self.state = not self.state
            return self.state
        return None
