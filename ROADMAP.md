# ROADMAP

핵심 원칙: **가장 위험한 가정부터, 최소 비용으로 검증한다.** UI·기능은 검증 통과 후에 얹는다.
이 프로젝트의 단일 최대 리스크는 "통신사가 이 트래픽을 테더링으로 잡는가" 이며, M1은 오직 이
질문에만 조준한다.

---

## 마일스톤 개요

| 단계 | 목표 | UI |
|------|------|-----|
| **M1 — Phase 1 MVP** ✅ | 통신사 미차감 검증 (전 구간 end-to-end) — **통과(2026-07-03)** | 거의 없음 (폰 토글 + Proxifier) |
| **M2 — android 안정화** | 상시 사용 가능한 폰 서버 | 폰 상태 화면 |
| **M3 — 기능 확장** | QUIC/UDP 등 커버리지 | — |
| **M4 — Phase 2 (선택)** | Proxifier 의존 제거(자체 클라이언트) | 윈도우 트레이 UI |

M1만으로 완성품이 될 수 있다. M4는 Proxifier가 싫어질 때의 선택지이며 필수가 아니다.

---

## M1 — Phase 1 MVP (최소 검증)

**목표**: "지정 프로그램 → 폰 셀룰러 → 통신사 무제한 데이터로 집계" 가 실제로 성립하는지 단일 검증.

### 범위 (최소)

- **android**: SOCKS5 `CONNECT(TCP)` 만. 셀룰러 강제 바인딩(`forceCellular`). 시작/정지 버튼 1개 + "실행중" 표시. 통계·로그·설정 화면 **없음**.
- **adb**: `adb forward tcp:1080 tcp:1080`.
- **windows**: Proxifier 규칙 **1개**(예: `chrome.exe` → SOCKS5 `127.0.0.1:1080`), `Resolve hostnames through proxy` **ON**.

### 명시적 제외 (M1에서 안 함)

- UDP/QUIC, UDP ASSOCIATE
- 폰 통계·로그·설정 UI, 자동 재시작
- adb 자동 감시 스크립트(수동 명령으로 충분)
- Phase 2 자체 클라이언트

### 검증 게이트 (GO / NO-GO) — ✅ **GO (2026-07-03)**

- [x] 대상 프로그램의 외부 IP = **폰 셀룰러 IP** — `curl --socks5-hostname`로 프록시 egress가 폰 셀룰러 APN(rmnet1) IP 확인
- [x] **통신사 테더링/공유 데이터 한도가 차감되지 않음 (← 핵심)** — 프록시 1GB 부하 → 통신사 공유 쿼터 6.66→6.67GB(≈0), 일반 데이터 −1.07GB
- [x] DNS도 폰 회선으로 감 (누수 없음) — SOCKS5 `--socks5-hostname`(ATYP=DOMAIN)으로 DNS를 폰이 셀룰러 해석. Proxifier `dnsleaktest`는 L2(실사용 세팅) 시 재확인
- [ ] 브라우징/영상이 쓸 만한 속도 — 다운로드 부하는 원활(수십 Mbps대). Chrome 실사용 체감은 L2에서

**검증 방식(요약)**: Proxifier 없이 `curl`로 두 경로를 차등 비교 — 테더 경로(핫스팟, plain `curl`) 300MB는 폰/통신사 테더링 카운터에 +0.40GB로 잡히고, 프록시 경로(재생성, `--socks5-hostname`) 1GB는 테더링 ≈0 + adb-tether **앱** 데이터로 집계(333MB→1.44GB). 폰 OS·통신사 per-app·통신사 과금 3레벨 일치. 상세는 [windows/M1-VALIDATION.md](windows/M1-VALIDATION.md) 결과 절.

**NO-GO(미차감 실패) 시 점검(참고용, 이번엔 GO)**: 셀룰러 바인딩이 실제로 동작했는지 / 폰이 WiFi로 샜는지 / IPv6 경로 / 통신사가 TTL 외 다른 신호(사용량·DPI)를 쓰는지.

---

## M2 — android 안정화

M1 통과 후, 폰 서버를 상시 사용 가능한 수준으로.

- [ ] 상태 UI: 연결 수 / 누적 송수신량 / 셀룰러 확보 여부
- [ ] (선택) 최근 연결 로그(목적지 host:port)
- [ ] 포트·`forceCellular` 등 설정값 노출
- [ ] 셀룰러 유실 감지 → 신규 연결 차단 + 알림 갱신
- [ ] 연결 상한/타임아웃/백프레셔, 자원 정리
- [x] 배터리 처리 — **배터리 최적화 예외 요청 버튼 완료(2026-07-03)**, 실기기 예외 허용됨
- [x] **부팅 자동실행 — BootReceiver로 재부팅 후 자동 시작(마지막 '시작' 상태일 때만). 실기기 재부팅 검증 완료(2026-07-03)**
- [ ] FGS 장시간 안정성(수 시간 화면꺼짐 실측)

---

## M3 — 기능 확장

- [ ] **UDP ASSOCIATE** — 크롬 QUIC(HTTP/3, UDP 443) 커버. (미지원 시 TCP 폴백에 의존)
- [ ] adb 자동 감시·재설정 스크립트(`watch.bat`) — USB 재연결 대응
- [ ] Proxifier 프로파일(`profile.ppx`) 버전관리, 대상 프로세스 다건화

---

## M4 — Phase 2 자체 클라이언트 (착수: 블랙리스트로 방향 결정 2026-07-03)

Proxifier 대체. **블랙리스트 모델**: 기본 전부 폰, 예외 프로세스만 Direct(사용자 결정).
구현 방식: **검증 엔진 기반 자체 Go 클라이언트**(TUN/DNS/프로세스매칭/IPv6는 mihomo·sing-box 엔진에 위임).

- [x] **Stage 0** — 블랙리스트 라우팅 개념 증명. **실기기 검증 완료(2026-07-03)**: 기본→폰 재생성, 예외→기존회선(프로세스별).
- [x] **Stage 1** — 예외를 별도 파일로 관리(`windows/client/exceptions.yaml`, mihomo rule-provider) + `run.bat`(관리자 자동요청·adb forward·mihomo 실행). Go 빌드 없이 네이티브로 요구사항 충족. **검증 완료.**
- [ ] **Stage 2(선택)** — 트레이 UI + 단일 exe(sing-box/mihomo 라이브러리 임베드, Go). 폴리시 원할 때만.
- 핵심 전제: 관리자 권한 + TUN 드라이버(WinTun). DNS 누수는 fake-ip로 해결(엔진).
- 예외 목록은 config 기반(사용자가 편집). QUIC/UDP 는 M3(폰 UDP) 완료 후 커버.

---

## 의존 관계

- M1의 android 서버·adb 채널은 모든 상위 단계에서 **그대로 재사용**된다.
- M4는 M1의 Proxifier만 교체할 뿐, 폰 서버를 다시 만들지 않는다.
- 따라서 M1은 버려지는 작업이 거의 없는 토대다.
