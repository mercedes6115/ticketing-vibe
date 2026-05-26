@echo off
echo ========================================
echo   Ticketing System - Start
echo ========================================
echo.

docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo Error: Docker is not running. Please start Docker Desktop first.
    exit /b 1
)

cd /d "%~dp0\.."

echo [1/2] Starting Docker services...
docker-compose up -d --build

echo.
echo [2/2] Checking service status...
docker-compose ps

echo.
echo Services started!
echo   Frontend:   http://localhost:5173
echo   Backend:    http://localhost:8080
echo   Grafana:    http://localhost:3000
echo   Prometheus: http://localhost:9090
echo   MySQL:      localhost:3306
echo   Redis:      localhost:6379
echo.
echo View logs:         docker-compose logs -f
echo View backend logs: docker-compose logs -f backend
echo Stop services:     scripts\stop.bat
echo.
