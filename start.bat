@echo off
echo ==========================================
echo Starting NEXUS Platform...
echo ==========================================

echo.
echo [1/3] Starting Infrastructure (Database, MQTT)...
cd infrastructure
docker compose up -d
cd ..

echo.
echo [2/3] Starting Backend (Spring Boot)...
start "NEXUS Backend" cmd /k "cd backend && mvnw.cmd spring-boot:run"

echo.
echo [3/3] Starting Frontend (Vite/React)...
start "NEXUS Frontend" cmd /k "cd frontend && npm run dev"

echo.
echo ==========================================
echo NEXUS is booting up!
echo.
echo Backend will be available at: http://localhost:8080
echo Frontend will be available at: http://localhost:5173
echo ==========================================
echo Note: Two new terminal windows have been opened for the backend and frontend logs.
