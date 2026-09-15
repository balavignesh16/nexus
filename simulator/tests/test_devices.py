from nexus_simulator.devices import SimulatedDevice


def test_multi_capability_device_builds_independent_read_and_write_capabilities():
    device = SimulatedDevice(
        device_id="dev-1",
        name="ESP32-SIM-001",
        serial_number="SIM-ESP32-SIM-001",
        capabilities=[
            {"capabilityType": "TEMPERATURE", "direction": "READ", "unit": "CELSIUS"},
            {"capabilityType": "HUMIDITY", "direction": "READ", "unit": "PERCENT"},
            {"capabilityType": "PRESSURE", "direction": "READ", "unit": "HPA"},
            {"capabilityType": "MOTION", "direction": "READ", "unit": "BOOLEAN"},
            {"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"},
        ],
    )

    assert set(device.read_capabilities.keys()) == {"TEMPERATURE", "HUMIDITY", "PRESSURE", "MOTION"}
    assert set(device.write_capabilities.keys()) == {"LED"}


def test_bme280_capabilities_share_one_underlying_model():
    device = SimulatedDevice(
        device_id="dev-1", name="n", serial_number="s",
        capabilities=[
            {"capabilityType": "TEMPERATURE", "direction": "READ", "unit": "CELSIUS"},
            {"capabilityType": "HUMIDITY", "direction": "READ", "unit": "PERCENT"},
            {"capabilityType": "PRESSURE", "direction": "READ", "unit": "HPA"},
        ],
    )

    temp_model = device.read_capabilities["TEMPERATURE"].generator.model
    humidity_model = device.read_capabilities["HUMIDITY"].generator.model
    pressure_model = device.read_capabilities["PRESSURE"].generator.model

    assert temp_model is humidity_model is pressure_model


def test_relay_capability_builds_one_actuator_for_all_configured_channels():
    device = SimulatedDevice(
        device_id="dev-1", name="n", serial_number="s",
        capabilities=[
            {"capabilityType": "RELAY", "direction": "WRITE", "unit": "BOOLEAN",
             "metadata": {"channels": [1, 2, 3, 4]}},
        ],
    )

    assert list(device.write_capabilities.keys()) == ["RELAY"]
    expected_channels = {1: False, 2: False, 3: False, 4: False}
    assert device.write_capabilities["RELAY"].actuator.channels == expected_channels


def test_unknown_capability_type_is_silently_skipped_not_fabricated():
    device = SimulatedDevice(
        device_id="dev-1", name="n", serial_number="s",
        capabilities=[{"capabilityType": "SOMETHING_FUTURE", "direction": "READ", "unit": "RAW"}],
    )

    assert device.read_capabilities == {}
    assert device.write_capabilities == {}
    assert device.has_capabilities() is False


def test_device_with_no_capabilities_reports_false():
    device = SimulatedDevice(device_id="dev-1", name="n", serial_number="s", capabilities=[])
    assert device.has_capabilities() is False


def test_capabilities_never_leak_between_two_device_instances():
    device_a = SimulatedDevice(
        device_id="a", name="A", serial_number="SIM-A",
        capabilities=[{"capabilityType": "LED", "direction": "WRITE", "unit": "BOOLEAN"}],
    )
    device_b = SimulatedDevice(
        device_id="b", name="B", serial_number="SIM-B",
        capabilities=[{"capabilityType": "FAN", "direction": "WRITE", "unit": "BOOLEAN"}],
    )

    device_a.write_capabilities["LED"].actuator.apply(True)

    assert "LED" not in device_b.write_capabilities
    assert device_b.write_capabilities["FAN"].actuator.state is False
