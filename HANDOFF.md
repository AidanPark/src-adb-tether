# HANDOFF — adb-tether

> 📌 **2026-07-06 주력 전환: 현재 권장 방식은 [MANUAL.md](MANUAL.md) (Every Proxy + 핫스팟 + 시스템 프록시).** SKT 미차감 검증·상시 작동. 아래 자작앱(USB) 방식은 **폴백으로 보존**. 배경·검증 결과는 프로젝트 메모리 `every-proxy-pivot` 참조.

새 세션(이전 대화 맥락 없음)이 즉시 이어받기 위한 문서. **여기부터 읽고**, 세부는
[README](README.md) → [ROADMAP](ROADMAP.md) → [windows/M1-VALIDATION.md](windows/M1-VALIDATION.md) 순으로.

프로젝트 위치: `C:\Users\j1445\project\src-adb-tether` (개인용 도구, 서비스/배포 아님).

---

## 1. 무엇을 / 왜

- **목표**: 폰의 무제한 셀룰러 데이터를 **노트북의 특정 프로그램**에 물리되, 통신사 테더링 한도에 걸리지 않게 한다.
- **문제**: 요금제 데이터는 무제한이지만 **테더링/핫스팟은 별도 한도**. 통신사는 테더링을 TTL 감소·OS 핑거프린트·DPI로 탐지한다. 일반 USB 테더링(RNDIS 포워딩)은 이 흔적이 남는다.
- **접근**: 포워딩 대신, 폰의 앱이 목적지로 **새 소켓을 직접 여는 재생성(re-origination)**. 통신사에는 "폰 앱 하나가 인터넷 쓰는 것"으로만 보인다(TTL 64 자연 발생, 포워딩 흔적 없음). TTL 트릭보다 근본적. (기성품 EasyTether와 같은 원리 — 단 자체 구현.)

---

## 2. 확정된 설계 결정 (근거 포함)

- **폰 = SOCKS5 재생성 서버 앱** (Android/Kotlin). 목적지로 native socket을 직접 연다.
- **전송 채널 = USB + `adb forward tcp:1080 tcp:1080`**. `reverse`가 **아님** — 서버가 폰에 있고 PC가 접속하므로 host→device인 `forward`. 루팅 불필요.
- **대상 = 노트북 1대만** (WiFi 라우터 안 씀). 여러 기기를 무선으로 붙이려면 GL.iNet 여행용 라우터가 대안이지만 **현재 범위 밖**.
- **PC측 라우팅 = 프로세스별 선택 (Proxifier)**. 지정한 .exe만 폰 회선, 나머지는 기존 회선(Direct). WiFi는 그대로 살아있고 지정 프로그램만 폰으로 빠진다.
- **★ 셀룰러 강제 바인딩**: 폰 앱은 소켓/DNS를 반드시 셀룰러 `Network`에 바인딩한다(`requestNetwork(TRANSPORT_CELLULAR)` + `network.socketFactory`). 안 하면 폰 WiFi가 켜져 있을 때 WiFi로 새서 무제한 데이터를 안 쓴다.
- **DNS 누수 방지**: Proxifier `Resolve hostnames through proxy` ON. SOCKS ATYP=DOMAIN을 폰이 셀룰러로 직접 해석.
- **제약**: TCP/UDP만. `ICMP`/raw IP 불가. **QUIC(UDP 443)** 는 1차 미지원 → 검증 시 Chrome QUIC 비활성화로 TCP 고정. 약관 위반 소지 및 행위 기반 탐지(대용량 사용량)는 회피 못 함.
- **권한 UI 안 만듦**: 런타임 요청이 필요한 건 `POST_NOTIFICATIONS`(API 33+) 하나뿐이고 거부해도 프록시는 동작 → OS 다이얼로그 1회로 충분.

---

## 3. 현재 상태

- **★ 최소 동작 성공(2026-07-03).** Proxifier 없이 `windows/chrome-phone.bat`(크롬 `--proxy-server=socks5://127.0.0.1:1080` + 전용 프로필)로 지정 크롬 창만 폰 셀룰러 재생성 경로로 egress 확인(헤드리스 검증: 프록시 경유 IP=rmnet1 e2ad, PC 기본=핫스팟 f09f). 임의 .exe 라우팅용 Proxifier는 미설치·후속.
- **★ M1 통과(2026-07-03) — 프로젝트 최대 리스크 해소.** 재생성 트래픽이 통신사 테더링으로 안 잡힘을 **실기기+실과금**으로 검증. 프록시 1GB 부하 → 통신사 공유(테더링) 쿼터 불변(6.66→6.67GB), 일반 데이터만 −1.07GB, adb-tether **앱** 데이터 +1.1GB(333MB→1.44GB). 대조로 테더 경로 300MB는 테더링 +0.40GB로 정상 집계. 상세는 [ROADMAP](ROADMAP.md) M1 게이트 · [M1-VALIDATION](windows/M1-VALIDATION.md) 결과 절.
- **android/**: 스캐폴딩 완료, **디버그 빌드+실기기 동작 검증됨**. `assembleDebug` 통과 · `app-debug.apk` 설치·실행·프록시 동작 확인. Kotlin 2.1 / AGP 8.7.3 / compile·target SDK 35 / minSdk 26. (빌드 시 Android Studio 번들 JBR을 `JAVA_HOME`으로. `android/local.properties`에 SDK 경로 생성해둠.)
- **M2 착수(2026-07-03)**: (1) 관찰성 — 알림/인앱 UI에 누적 연결수·↑↓바이트·셀룰러 라이브 표시, 셀룰러 유실 시 신규 연결 거부. (2) 배터리 최적화 예외 요청 버튼. (3) **부팅 자동실행** — `BootReceiver`가 마지막 '시작' 상태(pref)면 재부팅 후 서비스 자동 시작. **셋 다 실기기 검증됨(재부팅 포함).** 폰쪽 사실상 무인 상시구동. 사용법은 [USAGE.md](USAGE.md). 잔여 M2는 §4·ROADMAP.
- **windows/**: M1은 Proxifier 설정 + `adb forward`(빌드 대상 없음). 검증은 Proxifier 없이 `curl` 차등 비교로 진행함(L2 Proxifier 실사용 세팅은 미완). 자체 클라이언트(Phase 2, WinDivert/Go)는 미착수.
- **문서**: README · ROADMAP(M1 ✅) · android/DESIGN · windows/DESIGN · windows/M1-VALIDATION(결과 追記) 완비.
- **환경 이력**: 원래 WSL(`/home/aidan/projects/src-adb-tether`)에서 개발·빌드 검증 후 Windows로 이동, WSL 원본 삭제. 이제 adb는 Windows 네이티브 사용(WSL은 USB 미지원).

---

## 4. 다음 할 일 (바로 이어서)

M1 게이트(통신사 테더링 미차감)는 **통과 완료**. 다음은 실사용화(M2) 중심.

1. **M2 — android 안정화 (주력)**: 상태 UI·셀룰러 유실 차단·**배터리 최적화 예외 요청 버튼**은 **완료(2026-07-03)**. 잔여: 연결 상한/타임아웃/백프레셔·자원정리, 최근 연결 로그(목적지 host:port), 설정값(포트·forceCellular) 노출, FGS 장시간(수 시간 화면꺼짐) 안정성 실측. 범위는 [ROADMAP](ROADMAP.md) M2.
2. **L2 실사용 세팅(GUI 수동)**: **PC를 독립 회선(WiFi/유선)에 붙인 상태**에서 Proxifier로 `chrome.exe` 1규칙 + `Resolve hostnames through proxy` ON → Chrome 외부 IP·`dnsleaktest`·속도 체감 확인. 절차 M1-VALIDATION.md 3절. (검증 당시엔 PC가 폰 핫스팟에 붙어 있어 "선택 라우팅"은 순수 확인 못 함 — 독립 회선 필요.)
3. **통신사 앱 장기 관찰(선택)**: 며칠 실사용하며 공유(테더링) 쿼터가 계속 불변인지 재확인해 못 박기.

이후 M3(UDP/QUIC)·M4(자체 WinDivert 클라이언트로 Proxifier 대체)는 ROADMAP 참조.

> **재현 메모(다음 세션):** APK 빌드 = `cd android; JAVA_HOME="…/Android Studio/jbr" ./gradlew.bat assembleDebug`. 설치·실행 = `adb install -r …/app-debug.apk`; `adb shell am start -n com.adbtether/.MainActivity`; 폰에서 "시작"; `adb forward tcp:1080 tcp:1080`. `adb forward`는 USB 재연결/adb 재시작 시 날아가니 재설정 필요. 검증 기기 R3KL20CDTBD(멀티 APN, 셀룰러 prefix는 재접속마다 로테이션 — 정상).

---

## 5. 파일 지도

```
src-adb-tether/
├─ HANDOFF.md              # 이 문서
├─ README.md               # 개요·원리·사용법·문서 인덱스
├─ USAGE.md                # 실행/종료 사용법(부팅 자동실행 + PC 런처)
├─ ROADMAP.md              # M1~M4
├─ android/                # 폰 SOCKS5 재생성 서버 (빌드 검증됨)
│  ├─ DESIGN.md / README.md
│  └─ app/src/main/java/com/adbtether/
│     ├─ MainActivity.kt       # 시작/정지 토글 + 상태 + 배터리 예외 버튼
│     ├─ ProxyService.kt       # FGS(specialUse) 수명주기·알림·부팅 pref
│     ├─ BootReceiver.kt       # 부팅 후 자동 시작(마지막 '시작' 상태일 때만)
│     ├─ socks/Socks5Server.kt        # 127.0.0.1:1080 accept 루프
│     ├─ socks/Socks5Connection.kt    # SOCKS5 핸드셰이크+CONNECT+릴레이
│     ├─ socks/SocksConfig.kt         # 설정/상태 모델
│     └─ net/CellularNetwork.kt       # 셀룰러 강제 바인딩·소켓 재생성
└─ windows/
   ├─ DESIGN.md            # PC측 라우팅(Phase 1 Proxifier / Phase 2 자체)
   ├─ M1-VALIDATION.md     # L1→L2→L3 검증 체크리스트(Windows 명령)
   └─ client/              # mihomo 전체라우팅(폴백). ※ chrome-phone.bat 삭제됨 → 현재 방식은 MANUAL.md
```

---

## 6. 빌드 노트

- **Android Studio**: `android/` 폴더 열기 → `local.properties`(SDK 경로) 자동 생성, Gradle 동기화 → 빌드. `compileSdk 35`라 SDK Platform 35 필요(SDK Manager).
- **CLI**: 래퍼 포함. Windows PowerShell에서 `cd android; .\gradlew.bat assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk`.
- 요구: JDK 17, Android SDK(platform 35, build-tools 35).
