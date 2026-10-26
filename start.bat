@echo off
setlocal
cd /d "%~dp0"
rem Deterministic by default. Local opt-in: --ollama --model llama3.2:3b --allow-local-evidence
set "SPRING_PROFILES_ACTIVE=phase6-local"
echo Starting NEXUS Phase 6 from %CD%
echo Open http://localhost:5173/assistant after readiness checks complete.
python start_phase6.py %*
endlocal
