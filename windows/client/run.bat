@echo off
REM adb-tether one-click launcher: mihomo TUN router + USB tunnel keep-alive.
REM
REM Double-click. UAC asks for admin (needed for the TUN driver).
REM All PC traffic (incl. WSL) -> phone cellular (re-originated), except the
REM processes listed in exceptions.yaml. A background keeper re-establishes the
REM adb forward when the phone is unplugged/replugged, so internet auto-resumes.
REM
REM Prereq: phone Every Proxy HTTP :8080 ON + USB debugging.
REM To STOP: close this window (removes the TUN route; keeper self-exits).

REM --- helper mode: re-launched with KEEPFWD -> run only the forward keeper ---
if "%~1"=="KEEPFWD" goto keepfwd

REM --- self-elevate (TUN driver needs admin) ---
net session >nul 2>&1
if errorlevel 1 (
  echo Requesting administrator rights...
  powershell -Command "Start-Process -Verb RunAs cmd -ArgumentList '/c','\"%~f0\"'"
  exit /b
)

cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

REM initial USB tunnel (immediate connectivity; keeper re-applies it too)
"%ADB%" forward tcp:8080 tcp:8080

REM background keeper: no new window, auto-recovers forward on USB replug
start /b "" "%~f0" KEEPFWD

echo Starting mihomo (all traffic -^> phone, except EXCEPTIONS in config.yaml)...
echo Close this window to stop.
mihomo.exe -d .

echo.
echo mihomo stopped.
pause
exit /b

REM ===================== forward keeper (background) =====================
:keepfwd
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
:kfloop
REM self-exit once the main router (mihomo) is gone
tasklist /FI "IMAGENAME eq mihomo.exe" 2>nul | find /I "mihomo.exe" >nul || exit
"%ADB%" forward tcp:8080 tcp:8080 >nul 2>&1
ping -n 4 127.0.0.1 >nul
goto kfloop
