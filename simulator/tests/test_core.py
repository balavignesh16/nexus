import nexus_simulator
from nexus_simulator.config import SimulatorConfig
from nexus_simulator.core import main


def test_package_import():
    """Verify that the package imports correctly."""
    assert nexus_simulator.__version__ == "0.1.0"


def test_config_initialization():
    """Verify that SimulatorConfig initializes with expected defaults."""
    config = SimulatorConfig()
    assert config.version == "0.1.0"
    assert config.is_initialized is True


def test_main_execution(capsys):
    """Verify that the main entry point executes and outputs correctly."""
    main()
    captured = capsys.readouterr()
    assert "NEXUS Simulator Foundation Initialized" in captured.out
    assert "Status: Active" in captured.out
