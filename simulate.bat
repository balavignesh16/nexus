@echo off
echo ==========================================
echo Starting NEXUS Live Simulator Data Pump...
echo ==========================================
echo.
echo This script will provision test devices (if not already created)
echo and start an infinite loop sending live telemetry to the MQTT broker.
echo.
echo Press Ctrl+C in this window at any time to STOP the simulation.
echo ==========================================
echo.

cd simulator

echo [1/2] Provisioning Test Devices...
call .\.venv\Scripts\activate
python setup_test_devices.py
echo.

echo [2/2] Starting Telemetry Data Pump...
echo Pumping data via MQTT (Interval: ~5 seconds)...
python -m nexus_simulator

cd ..
