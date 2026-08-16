# v2 Module – UDP‑ASSOCIATE SOCKS5 Proxy

## Overview
This module provides a **stand‑alone** implementation of the original `src‑adb‑tether` proxy that adds **UDP ASSOCIATE** support (required by Parsec and other UDP‑based applications).  It lives completely under `src‑adb‑tether/v2` and does **not** modify any existing code in the original `android/` or `windows/` directories.

---

## Directory Layout
```
src‑adb‑tether/v2/
├─ android/                 # Android implementation (V2)
│   └─ app/src/main/kotlin/com/aidan/
│       ├─ ProxyServiceV2.kt       # Foreground service; starts TCP & UDP handlers
│       ├─ Socks5ServerV2.kt       # Original SOCKS5 TCP server (unchanged)
│       └─ UdpAssociateHandler.kt # UDP‑ASSOCIATE (FRAG‑0) implementation
│   ├─ AndroidManifest.xml
│   └─ build.gradle.kts
├─ windows/client/          # Windows front‑end for V2
│   ├─ run_v2.bat                # Starts ADB forward, launches V2 service, runs mihomo
│   └─ config_v2.yaml            # Same as original config.yaml + `udp: true`
└─ README.md                # **You are reading this file**
```

---

## Build & Install (Android)
```bash
# From the project root
cd /Users/aidan/projects/src-adb-tether
# Build only the V2 Android module
./gradlew :v2:android:assembleRelease
# Install the APK (it has a different package name: com.aidan.v2)
adb install -r v2/android/app/build/outputs/apk/release/app-release.apk
```
The APK can coexist with the original `com.aidon` app because it uses a distinct package identifier.

---

## Windows – Run the V2 Proxy
```bat
cd C:\Users\aidan\projects\src‑adb‑tether\v2\windows\client
run_v2.bat
```
`run_v2.bat` performs three actions:
1. **`adb forward tcp:1080 tcp:1080`** – forwards the local TCP port (UDP uses the same port).  
2. **Starts the V2 foreground service** (`com.aidan.v2/.ProxyServiceV2`).  
3. **Launches `mihomo.exe`** with `config_v2.yaml`.  
The config file contains the line `udp: true`, instructing Mihomo to forward UDP‑ASSOCIATE requests to the Android proxy.

---

## Using Parsec (or any UDP‑based app)
1. Open Parsec → *Settings → Network* → **SOCKS5 Proxy**.
2. Set **Host** to `127.0.0.1` and **Port** to `1080`.
3. Enable **Use UDP for games** (Parsec enables this automatically when a SOCKS5 proxy is configured).
4. Connect.  Parsec will now use the **UDP ASSOCIATE** flow:
   - Parsec → Windows → `mihomo` (TCP/UDP) → Android V2 service → **Cellular network** (re‑originated traffic).
   - All traffic goes through the phone’s cellular data, keeping the carrier’s tether‑limit untouched.

---

## Troubleshooting
| Symptom | Check | Fix |
|---------|-------|-----|
| **No internet** after start | `adb devices` shows the device? | Re‑plug USB or reconnect Wi‑Fi Direct; run `run_v2.bat` again. |
| **Parsec shows “Unable to connect”** | `curl -x http://127.0.0.1:1080 https://api.ipify.org` returns your **cellular IP**? | If it returns your PC’s IP, the proxy isn’t active – ensure `run_v2.bat` started the V2 service (`adb shell ps | grep ProxyServiceV2`). |
| **UDP traffic still goes over Wi‑Fi** | Look at `mihomo` logs – there should be a line `udp associate to phone-p2p`. | Verify `config_v2.yaml` contains `udp: true` and that `run_v2.bat` used `config_v2.yaml` (not the original config). |
| **App crashes** | Check Android logcat: `adb logcat | grep ProxyServiceV2`. | Most crashes are due to missing `FOREGROUND_SERVICE_SPECIAL_USE` permission – ensure the app is compiled with the manifest shown above. |

---

## Clean‑up
When you are done, simply close the `run_v2.bat` console window.  This will:
- Stop `mihomi.exe` (the TUN router).
- Stop the UDP/TCP forwarder.
- The foreground service on the phone will automatically stop when `mihomi` exits.

If you need to uninstall the V2 app:
```bash
adb uninstall com.aidan.v2
```
---

## Summary
- **No changes** to the original project – everything lives under `src‑adb‑tether/v2`.  
- **UDP ASSOCIATE** is now supported, enabling Parsec and other UDP‑heavy applications to work over the cellular‑only tethering setup.  
- Follow the short build/run steps above and you’re ready to stream games without consuming your carrier’s tether‑quota.

---

*Enjoy wireless, carrier‑friendly gaming!*
