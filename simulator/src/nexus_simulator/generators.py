import random
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
    """Random boolean generator for Motion Sensor (mostly false)."""
    
    def generate(self) -> bool:
        return random.random() > 0.8  # 20% chance of motion
