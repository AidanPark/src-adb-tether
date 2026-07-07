# M1 실기기 검증 체크리스트 (Windows)

목표(단일 게이트): **지정 프로그램 → 폰 셀룰러 → 통신사가 "테더링/공유 데이터"가 아니라
"일반 데이터"로 집계** 하는지 확인. 이게 성립해야 M1 통과.

검증은 **아래 계층부터** 쌓는다. 안 되면 어느 층 문제인지 바로 좁혀진다.

- **L1** 폰 앱 + `adb forward` 단독 (Proxifier 없이 `curl`로)
- **L2** Proxifier 프로세스별 라우팅
- **L3** 통신사 차감 확인 ← 진짜 게이트

---

## ✅ 검증 결과 (2026-07-03) — GO

기기 **R3KL20CDTBD**(KT계열 `2001:2d8::/32`, 멀티 APN). L2(Proxifier)는 건너뛰고 **`curl` 차등 비교**로
게이트를 직접 검증함(Proxifier 변수·QUIC 배제). 폰이 WiFi 꺼짐 · 셀룰러만 ON 상태.

| 경로 | 방법 | 부하 | 테더링 카운터 변화 | 일반/앱 데이터 |
|------|------|------|-------------------|----------------|
| **테더(핫스팟)** | plain `curl.exe` | 300MB | **+0.40GB** (정상 집계) | — |
| **프록시(재생성)** | `curl.exe --socks5-hostname` | 1GB | **≈0** (통신사 공유 6.66→6.67GB, 삼성 테더링 flat) | 통신사 일반 −1.07GB · adb-tether **앱** 333MB→1.44GB |

- 재생성 소켓은 폰 자체 데이터 APN(`rmnet1`)으로 egress, 핫스팟은 테더 APN(`rmnet2`/`ap_br_swlan0`)로 분리 — IPv6 prefix 레벨에서 구분됨.
- **폰 OS 테더링 집계 · 통신사 per-app 집계 · 통신사 과금 쿼터 3레벨 전부 일치** → 재생성이 테더링 탐지/과금을 실제 회피.
- 미측정/후속: L2 Proxifier 실사용(독립 회선에서 `chrome.exe` 선택 라우팅 + `dnsleaktest` + 속도 체감), 통신사 앱 장기 관찰.

> 참고 미터: 삼성 **설정 → 연결 → 모바일 핫스팟 및 테더링 → 데이터 사용량**(폰 OS 실시간, 테더링만 격리)이
> 통신사 앱보다 갱신이 빠르고 노이즈가 없어 차등 판정에 유용했음.

---

## 0. 사전 준비

- **폰**: 개발자 옵션 → **USB 디버깅 ON**. **셀룰러 데이터 ON**, 무제한 요금제.
  - 1차 검증은 **폰 WiFi를 끄고** 셀룰러만으로 → "폰이 셀룰러로 나가는가"를 애매함 없이 확인.
  - 나중에 폰 WiFi 켠 상태에서도 셀룰러로 나가는지(앱의 강제 바인딩 동작) 재확인.
- **PC**: `adb` (Android Studio SDK 동봉: `%LOCALAPPDATA%\Android\Sdk\platform-tools`),
  Proxifier 설치. `curl.exe`는 Windows 10/11 기본 포함.
- **APK**: Android Studio에서 빌드했거나
  `android\app\build\outputs\apk\debug\app-debug.apk` 존재.

> PowerShell에서는 `curl` 이 `Invoke-WebRequest` 별칭이므로 반드시 **`curl.exe`** 로 호출.

---

## 1. 설치 & USB 터널

```powershell
# 프로젝트 루트에서
cd C:\Users\j1445\project\src-adb-tether\android

# 기기 인식 (처음이면 폰에 "이 컴퓨터에서 USB 디버깅 허용?" 팝업 → 허용)
adb devices

# APK 설치 (Android Studio의 Run ▶ 로 대체 가능)
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 폰: adb-tether 앱 실행 → "시작" 탭 → 알림 권한 허용
#     화면에 "실행중", 알림에 "셀룰러 OK" 표시 확인
adb shell am start -n com.adbtether/.MainActivity   # 앱 띄우기(시작 탭은 수동)

# USB 터널: PC localhost:1080 -> 폰 1080(SOCKS 서버)
adb forward tcp:1080 tcp:1080
adb forward --list        # "tcp:1080 tcp:1080" 보이면 OK
```

---

## 2. L1 — 폰 앱 + adb 단독 검증 (Proxifier 없이)

`curl.exe` 를 SOCKS5로 직접 물려서, 재생성 경로 전체를 Proxifier 없이 시험한다.
`--socks5-hostname` 은 DNS까지 프록시(=폰)로 넘기므로 누수도 함께 확인된다.

```powershell
# 이 요청이 폰 셀룰러로 나가는지 → 반환 IP가 폰의 셀룰러 IP여야 함
curl.exe --socks5-hostname 127.0.0.1:1080 https://ifconfig.me
echo ---
# 비교용: 프록시 없이 PC 기본 회선의 IP
curl.exe https://ifconfig.me
```

**판정**
- 위(프록시) IP ≠ 아래(PC 기본) IP, 그리고 위 IP가 **모바일 통신사 IP** → L1 OK.
- 위가 실패/타임아웃 → 폰 앱 "시작" 안 됨 or "셀룰러 대기" 상태 (3-트러블슈팅).
- 위 IP가 PC WiFi와 같음 → 폰이 WiFi로 샘 → 폰 WiFi 끄고 재시도(강제 바인딩 확인).

---

## 3. L2 — Proxifier 프로세스별 라우팅

L1이 통과해야 의미 있음. Chrome 하나만 폰 회선으로 보낸다.

**Proxifier 설정**
1. Profile → **Proxy Servers** → Add: `127.0.0.1` / `1080` / **SOCKS5**.
2. Profile → **Name Resolution** → **Resolve hostnames through proxy = ON** (DNS 누수 방지, 필수).
3. Profile → **Rules** → Add:
   - Applications: `chrome.exe`
   - Action: **Proxy 127.0.0.1:1080 (SOCKS5)**
   - Default 규칙: **Direct**

**검증 (Chrome에서)**
- 이미 실행 중이던 Chrome은 **완전히 종료 후 재시작**(백그라운드 프로세스까지). 안 그러면 규칙 미적용.
- `https://ifconfig.me` 또는 `https://whatismyipaddress.com` → **폰 셀룰러 IP** 여야 함.
- `https://www.dnsleaktest.com` (Standard test) → 통신사 DNS로 나오면 누수 없음(WiFi ISP면 누수).
- 다른 앱(예: PowerShell `curl.exe https://ifconfig.me`)은 **PC 기본 회선 IP** 유지 → 선택 라우팅 확인.

> **QUIC 주의**: Chrome은 UDP(QUIC/HTTP3)를 선호하는데 이 프록시는 TCP만 처리한다.
> IP가 안 바뀌거나 일부만 새면 QUIC 때문일 수 있음 →
> `chrome://flags` → **Experimental QUIC protocol = Disabled** 후 재시작
> (또는 Proxifier에서 UDP 차단). M1은 이렇게 TCP로 고정해 검증.

---

## 4. L3 — 통신사 차감 확인 (★ 진짜 게이트)

```
[검증 전] 통신사 앱/고객센터에서 "테더링(공유) 데이터" 잔량과 "일반 데이터" 사용량 기록
   ↓
[부하]  Chrome(프록시 ON)으로 수백 MB 소비 — 예: 유튜브/넷플릭스 몇 분, 대용량 다운로드
   ↓
[검증 후] 두 수치 재확인
```

**판정 (GO / NO-GO)**
- ☑ **테더링(공유) 잔량 변화 없음** + 일반 데이터에서 차감 → **GO** (M1 통과)
- ☐ 테더링 잔량이 소비량만큼 줄어듦 → **NO-GO**

**NO-GO 시 점검**
- 폰이 실제로 셀룰러로 나갔나 (L1에서 셀룰러 IP 확인됐나, 폰 WiFi 꺼졌나).
- 통신사가 **IPv6** 경로로 탐지했나 (앱의 셀룰러 바인딩이 IPv6도 커버하는지).
- TTL 외 **사용량/행위 기반** 탐지 가능성 → M1 범위를 넘어 원인 규명 필요.

---

## 5. 트러블슈팅

| 증상 | 점검 |
|------|------|
| `adb devices` 비어 있음 | USB 디버깅 ON / **데이터 케이블**(충전 전용 X) / 폰 "허용" 팝업 / 드라이버 |
| `adb forward` 됐는데 `curl` 타임아웃 | 폰 앱 "시작" 안 됨, 또는 알림이 "셀룰러 대기" → 셀룰러 데이터 ON 확인 |
| 프록시 IP가 PC WiFi와 동일 | 폰이 WiFi로 나감 → 폰 WiFi 끄기 / 강제 바인딩(`CellularNetwork`) 동작 확인 |
| Chrome이 계속 Direct | `chrome.exe` 프로세스 완전 종료 후 재시작 / 규칙 프로세스명 확인 |
| 일부만 프록시 탐 | QUIC(UDP) → Chrome QUIC 비활성화 또는 Proxifier UDP 차단 |
| USB 뽑으면 대상 앱 인터넷 끊김 | 정상 — 터널이 끊기면 규칙 대상은 실패(폴백 안 함). `adb forward` 재설정 |

---

## 정리 (검증 종료 시)

```powershell
adb forward --remove tcp:1080        # 터널 해제
# 폰: 앱에서 "정지"
```

Proxifier는 규칙을 꺼두거나 프로파일을 분리해두면 평소엔 영향 없음.
