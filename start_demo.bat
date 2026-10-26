@echo off
setlocal
cd /d "%~dp0"
echo Starting the SIMULATED three-ESP demonstration from %CD%
echo Start the NEXUS backend first. Open http://localhost:5173/simulation
python demo-simulation/server.py %*
endlocal
