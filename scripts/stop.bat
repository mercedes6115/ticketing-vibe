@echo off
echo ========================================
echo   Ticketing System - Stop
echo ========================================
echo.

cd /d "%~dp0\.."

echo Stopping Docker services...
docker-compose down

echo.
echo Services stopped. Data volumes are preserved.
echo To remove all data: scripts\flush.bat
echo.
