@echo off
echo ========================================
echo   Ticketing System - Flush
echo ========================================
echo.
echo WARNING: This will remove ALL data volumes!
echo   - MySQL database data
echo   - Redis cache data
echo   - Gradle cache
echo   - Prometheus / Grafana data
echo.
set /p CONFIRM="Are you sure? (yes/no): "

if /i not "%CONFIRM%"=="yes" (
    echo Operation cancelled.
    exit /b 0
)

cd /d "%~dp0\.."

echo.
echo Stopping services and removing volumes...
docker-compose down --volumes

echo.
echo All services stopped and data volumes removed.
echo To start fresh: scripts\start.bat
echo.
