import pytest

from nexus_simulator.actuators import (
    ActuatorValidationError,
    BuzzerActuator,
    FanActuator,
    LedActuator,
    RelayActuator,
    ServoActuator,
)


def test_led_actuator_applies_boolean_state():
    led = LedActuator()
    led.apply(True)
    assert led.state is True
    led.apply(False)
    assert led.state is False


def test_led_actuator_rejects_non_boolean():
    led = LedActuator()
    with pytest.raises(ActuatorValidationError) as exc:
        led.apply("ON")
    assert exc.value.reason == "MALFORMED"


def test_fan_and_buzzer_actuators_apply_boolean_state():
    fan = FanActuator()
    fan.apply(True)
    assert fan.state is True

    buzzer = BuzzerActuator()
    buzzer.apply(True)
    assert buzzer.state is True


def test_servo_actuator_accepts_valid_angle():
    servo = ServoActuator()
    servo.apply(90)
    assert servo.angle == 90
    servo.apply(0)
    assert servo.angle == 0
    servo.apply(180)
    assert servo.angle == 180


@pytest.mark.parametrize("invalid_value", [-1, 181, 200, "90", 90.5, True])
def test_servo_actuator_rejects_invalid_angle(invalid_value):
    servo = ServoActuator()
    with pytest.raises(ActuatorValidationError) as exc:
        servo.apply(invalid_value)
    assert exc.value.reason == "MALFORMED"


def test_relay_actuator_tracks_independent_channel_state():
    relay = RelayActuator(channels=[1, 2, 3, 4])
    relay.apply({"channel": 1, "state": True})
    relay.apply({"channel": 3, "state": True})

    assert relay.channels == {1: True, 2: False, 3: True, 4: False}


def test_relay_actuator_rejects_invalid_channel():
    relay = RelayActuator(channels=[1, 2, 3, 4])
    with pytest.raises(ActuatorValidationError) as exc:
        relay.apply({"channel": 5, "state": True})
    assert exc.value.reason == "MALFORMED"


@pytest.mark.parametrize("invalid_value", [
    True,
    "not a dict",
    {"channel": 1},
    {"state": True},
    {"channel": "1", "state": True},
    {"channel": 1, "state": "on"},
])
def test_relay_actuator_rejects_malformed_value(invalid_value):
    relay = RelayActuator(channels=[1, 2, 3, 4])
    with pytest.raises(ActuatorValidationError) as exc:
        relay.apply(invalid_value)
    assert exc.value.reason == "MALFORMED"


def test_relay_actuator_only_creates_one_actuator_for_all_four_channels():
    relay = RelayActuator(channels=[1, 2, 3, 4])
    assert len(relay.channels) == 4
