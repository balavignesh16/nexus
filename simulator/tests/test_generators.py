from nexus_simulator.generators import TemperatureGenerator, GasGenerator, MotionGenerator

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
