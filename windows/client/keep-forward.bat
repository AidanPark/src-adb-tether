@echo off
REM adb-tether: keep the USB tunnel (adb forward tcp:8080) alive.
REM
REM Run this ALONGSIDE mihomo (run.bat). No admin needed.
REM When the phone is unplugged and replugged, this re-establishes the
REM adb forward within a few seconds so internet resumes automatically.
REM (mihomo keeps running; it just needs the forward back.)
REM
REM To STOP: close this window.

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

echo Keeping adb forward tcp:8080 -^> phone:8080 alive.
echo Unplug/replug the phone and the tunnel auto-recovers. Close window to stop.
echo.

:loop
REM wait-for-device blocks (no busy loop / no error spam) until phone is back
"%ADB%" wait-for-device
"%ADB%" forward tcp:8080 tcp:8080 >nul 2>&1
timeout /t 3 /nobreak >nul
goto loop
