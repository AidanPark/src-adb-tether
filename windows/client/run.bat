@echo off
REM adb-tether blacklist router (Stage 0) - self-elevating launcher.
REM Double-click. UAC will ask for admin (needed for TUN driver).
REM
REM Prereq: phone Every Proxy HTTP :8080 ON + USB, and adb forward tcp:8080 tcp:8080.
REM To STOP: close this window (or Ctrl+C). Closing removes the TUN route.

net session >nul 2>&1
if errorlevel 1 (
  echo Requesting administrator rights...
  powershell -Command "Start-Process -Verb RunAs cmd -ArgumentList '/c','\"%~f0\"'"
  exit /b
)

cd /d "%~dp0"

REM Make sure the USB tunnel is set (harmless if already set)
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
"%ADB%" forward tcp:8080 tcp:8080

echo Starting mihomo (all traffic -^> phone, except EXCEPTIONS in config.yaml)...
echo Close this window to stop.
mihomo.exe -d .
pause
