from nexus_simulator.generators import (
    Bme280HumidityGenerator,
    Bme280Model,
    Bme280PressureGenerator,
    Bme280TemperatureGenerator,
    ButtonGenerator,
    GasGenerator,
    MotionGenerator,
    StatefulMotionGenerator,
    TemperatureGenerator,
)


def test_temperature_generator_bounds():
    gen = TemperatureGenerator(current_value=34.9)
    # Run multiple times to trigger a walk past the boundary
    for _ in range(100):
        val = gen.generate()
        assert 20.0 <= val <= 35.0

def test_gas_generator_bounds():
    gen = GasGenerator(current_value=990)
    for _ in range(100):
        val = gen.generate()
        assert 0 <= val <= 1000

def test_motion_generator():
    gen = MotionGenerator()
    val = gen.generate()
    assert isinstance(val, bool)


def test_bme280_temperature_bounds():
    model = Bme280Model()
    gen = Bme280TemperatureGenerator(model)
    for _ in range(200):
        val = gen.generate()
        assert 20.0 <= val <= 35.0


def test_bme280_humidity_bounds():
    model = Bme280Model()
    gen = Bme280HumidityGenerator(model)
    for _ in range(200):
        val = gen.generate()
        assert 30.0 <= val <= 70.0


def test_bme280_pressure_bounds():
    model = Bme280Model()
    gen = Bme280PressureGenerator(model)
    for _ in range(200):
        val = gen.generate()
        assert 980.0 <= val <= 1030.0


def test_bme280_shares_one_correlated_model_across_capabilities():
    model = Bme280Model()
    temp_gen = Bme280TemperatureGenerator(model)
    humidity_gen = Bme280HumidityGenerator(model)

    temp_gen.generate()
    # The humidity generator reads the same model instance the temperature
    # generator just mutated - proving one shared state, not three
    # unrelated random walks.
    assert humidity_gen.model is model
    assert humidity_gen.model.temperature == model.temperature


def test_stateful_motion_generator_holds_true_for_retrigger_window():
    clock = {"t": 0.0}
    gen = StatefulMotionGenerator(
        trigger_probability=1.0, hold_seconds=5.0, clock=lambda: clock["t"]
    )

    assert gen.generate() is True  # triggers immediately (probability=1.0)
    clock["t"] = 2.0
    assert gen.generate() is True  # still within the hold window

    clock["t"] = 10.0
    gen.trigger_probability = 0.0
    assert gen.generate() is False  # hold window expired, no new trigger


def test_button_generator_returns_none_when_no_state_change():
    gen = ButtonGenerator(press_probability=0.0)
    assert gen.generate() is None


def test_button_generator_emits_only_on_state_transition():
    gen = ButtonGenerator(press_probability=1.0)
    first = gen.generate()
    assert first is True  # pressed
    second = gen.generate()
    assert second is False  # released (toggled again)
