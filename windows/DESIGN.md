# windows/ — PC측 클라이언트 (설계)

PC의 **지정한 프로세스** 트래픽만 SOCKS5 프록시(`127.0.0.1:1080`)로 보내고, 나머지는 기존
회선을 그대로 쓰게 한다. 그 프록시는 `adb forward` 를 통해 USB로 폰의 SOCKS5 서버에 연결된다.

windows/ 의 책임은 두 가지다.
1. **adb USB 채널 셋업·유지** (`adb forward`, 기기 감시).
2. **프로세스별 선택 라우팅** (어떤 .exe만 폰 회선을 태울지).

---

## 1. 접근 방식 결정 (2단계)

프로세스 단위로 트래픽을 투명하게 가로채 SOCKS로 보내는 것은 윈도우에서 난이도가 있다(커널/
WFP 레벨). 그래서 단계를 나눈다.

| 단계 | 라우팅 수단 | 목적 |
|------|-------------|------|
| **Phase 1 (권장 시작점)** | **Proxifier**(상용) + adb 스크립트 | 전체 파이프라인을 빠르게 end-to-end 검증 |
| **Phase 2 (선택)** | 자체 클라이언트(WinDivert 등) | 외부 상용툴 의존 제거 |

> 결정 기록: 윈도우측의 본질은 "검증된 프로세스 라우팅"이고 Proxifier가 이미 이를 안정적으로
> 한다. 따라서 **Phase 1을 기본**으로 하고, 자체 구현(Phase 2)은 의존성을 없애고 싶을 때의
> 선택지로 둔다. android/ 서버가 진짜 빌드 대상이고, 윈도우는 설정+자동화가 핵심이다.

---

## 2. Phase 1 — Proxifier 기반

### 2.1 adb 자동화 스크립트

폰 서버는 폰 `1080`, PC측은 `localhost:1080`. 서버가 **폰에 있으므로 `forward`**(reverse 아님).

```bat
:: windows/scripts/connect.bat
adb start-server
adb wait-for-device
adb forward tcp:1080 tcp:1080
echo forward 1080 -> device:1080 OK
```

- 기기 분리/재연결 시 forward가 끊기므로, 폴링으로 감시·재설정하는 watch 스크립트도 둔다.
- (선택) `adb devices` 로 권한·연결 상태 점검, 미연결 시 경고.

### 2.2 Proxifier 설정

- **Proxy 등록**: `SOCKS5`, `127.0.0.1`, `1080`.
- **Rules (프로세스 단위)**:

  | 규칙 | 대상 프로세스 | 동작 |
  |------|---------------|------|
  | 1 | `idea64.exe` (IntelliJ) | → SOCKS5 프록시 |
  | 2 | `chrome.exe` | → SOCKS5 프록시 |
  | 3 | `java.exe` (Maven/Gradle 빌드) | → SOCKS5 프록시 |
  | Default | 그 외 전부 | `Direct` (기존 회선) |

- **`Resolve hostnames through proxy` = ON** — 안 켜면 DNS 조회가 기존 회선으로 새서(DNS leak)
  분리가 깨진다. 우회 목적이면 필수.
- 설정은 `windows/proxifier/profile.ppx` 로 내보내 버전관리.

### 2.3 검증

- 대상 프로그램에서 외부 IP 조회 → **폰 셀룰러 IP**로 나오는지.
- 비대상 프로그램은 기존 회선 IP 유지.
- 통신사 테더링/공유 데이터 한도가 **차감되지 않는지** 확인.
- DNS 누수 점검(대상 앱의 DNS도 폰 회선으로 가는지).

---

## 3. Phase 2 — 자체 클라이언트 (선택)

Proxifier 의존을 없애려면 직접 만든다. 프로세스별 패킷 가로채기가 핵심 난제다.

### 3.1 라우팅 방식 후보

| 방식 | 설명 | 비고 |
|------|------|------|
| **WinDivert** | 유저스페이스에서 패킷 캡처/우회. SOCKET 레이어로 연결의 **PID 식별** 가능 → 대상 PID만 로컬 리다이렉터로 우회 | 가장 현실적. 선행 사례 존재(ProxiFyre 등) |
| Windows Packet Filter (winpkfilter) | 상용/오픈 혼재 NDIS 필터 | ProxiFyre 기반 기술 |
| WFP callout 드라이버 | 커널 레벨, 가장 강력하나 서명·복잡도 큼 | 과함 |
| wintun + tun2socks | TUN으로 전량/IP기준 라우팅 | **프로세스 단위가 native하지 않음** → 목적에 부적합 |

→ **WinDivert 기반**을 1순위로 둔다.

### 3.2 구성요소 (WinDivert안)

```
[대상 프로세스] ──outbound TCP──▶ WinDivert 캡처
                                     │ PID가 대상 목록에 있나?
                          ┌──────────┴──────────┐
                        예│                      │아니오 → 그대로 통과(Direct)
                          ▼
                 로컬 SOCKS 리다이렉터
                  - 연결을 가로채 127.0.0.1:1080(SOCKS5)로 재연결
                  - 원 목적지로 CONNECT 후 릴레이
                          │
                          ▼ adb forward(USB) → 폰 서버
```

- **Process selector** — 대상 프로세스명/경로 목록(config).
- **Packet interceptor** — WinDivert, 대상 PID의 outbound 연결만 우회.
- **SOCKS5 redirector** — 가로챈 TCP를 `127.0.0.1:1080` 으로 SOCKS5 CONNECT, 양방향 릴레이.
- **DNS 처리** — 대상 프로세스의 DNS(UDP 53)도 SOCKS 경로로 보내야 누수가 없다(폰 서버 UDP 지원 또는 TCP-DNS-over-SOCKS 필요).
- **adb 관리자** — Phase 1 스크립트 로직을 내장(forward 설정·감시).
- **설정/실행 UI** — 트레이 아이콘 또는 CLI + config 파일.

### 3.3 구현 언어 결정 (열림)

- 패킷 레벨 작업이라 **Go**(또는 Rust) 권장 — WinDivert/wintun 바인딩 성숙, 단일 정적 exe, clash/mihomo 등 선례.
- Kotlin/JVM은 안드로이드와 통일성은 있으나 패킷 캡처에 부적합 → 비권장.
- 결론: **Phase 2 진행 시 Go**. (Phase 1만으로 충분하면 미구현.)

---

## 4. 프로젝트 구조 (예정)

```
windows/
├─ scripts/
│  ├─ connect.bat        # adb forward 설정
│  └─ watch.bat          # 기기 감시·재설정
├─ proxifier/
│  └─ profile.ppx        # Proxifier 프로파일(규칙·DNS 설정)
└─ client/               # (Phase 2, 선택) 자체 클라이언트(Go)
   └─ ...
```

---

## 5. 미해결 / 주의

- **QUIC/HTTP3 (UDP 443)** — 크롬 등은 UDP를 선호. 폰 서버가 UDP 미지원이면 Proxifier에서 UDP를
  막거나, 앱이 TCP로 폴백하도록 둔다. 완전 처리는 폰 UDP ASSOCIATE + 윈도우 UDP 라우팅 양쪽 필요.
- **DNS leak** — Phase 1은 `Resolve hostnames through proxy` 로 해결. Phase 2는 직접 처리해야 함.
- **`ICMP`/raw IP** — SOCKS 경로 밖. ping 등은 폰 회선을 안 탄다.
- **약관·정책** — 통신사 약관 및 (해당 시) 회사 보안정책 저촉 여부는 사용자가 사전 확인.
- `adb forward` 는 USB 분리 시 끊김 → 재연결 감시 필요.
