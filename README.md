# adb-tether

> 📌 **현재 권장 방식은 [MANUAL.md](MANUAL.md) (Every Proxy + 핫스팟 + 시스템 프록시).** 2026-07-06 SKT 검증, 상시 작동 중. 아래 문서는 초기에 만든 **자작앱(USB) 방식**으로, 이제 폴백/참고용이다. (전환 배경: 프로젝트 메모리 `every-proxy-pivot`)

USB(adb)로 연결된 안드로이드 폰을 SOCKS5 프록시로 사용해, 윈도우 PC의 **특정 프로그램** 트래픽만 폰의 셀룰러 데이터로 내보내는 개인용 도구.

폰에서 트래픽을 **재생성(re-origination)** 하기 때문에, 통신사 입장에서는 단순 USB 테더링(패킷 포워딩)이 아니라 "폰의 앱 하나가 인터넷을 쓰는 것"으로 보인다.

> **이어받는 세션은 [HANDOFF.md](HANDOFF.md) 부터 읽을 것** — 목적·설계 결정 근거·현재 상태·다음 단계 요약.

---

## 개요 (동기)

- 폰 요금제의 데이터는 무제한이지만, **테더링/핫스팟 데이터는 별도 한도**가 걸려 있다.
- 통신사는 테더링을 주로 TTL 감소·OS 핑거프린트·DPI 같은 "남이 만든 패킷" 흔적으로 판별한다.
- 일반 USB 테더링(RNDIS)은 폰이 L3 라우터로 **패킷을 포워딩**하므로 위 흔적이 그대로 남는다 → 테더링으로 집계됨.
- 이 프로젝트는 포워딩 대신, 폰의 앱이 목적지로 **새 소켓을 직접 여는** 방식(재생성)을 쓴다. 통신사 망에는 폰이 직접 만든 트래픽(TTL 64, 정상 OS 핑거프린트)만 도착한다.

> ⚠️ 통신사 약관 위반 소지가 있다. TTL 등 1차 탐지는 무력화되지만, 비정상적 대용량 사용 같은 **행위 기반 탐지**까지 회피하지는 못한다. 개인 책임 하에 사용.

---

## 동작 원리

```
[윈도우 프로그램]                              ┌─ 폰 SOCKS5 서버 ─┐
   (idea64.exe,                                │  앱이 목적지로    │
    chrome.exe ...)                            │  '새 소켓'을 연다 │──▶ 셀룰러 ──▶ 통신사
        │                                      └──────────────────┘
        ▼  SOCKS5                                       ▲
   127.0.0.1:1080  ──── adb forward (USB) ───────── phone:1080
        │
   (그 외 프로그램은 건드리지 않음 → 기존 회선 그대로)
```

1. **폰 (android/)** — `127.0.0.1:1080` 에서 대기하는 SOCKS5 서버 앱. SOCKS 요청을 받으면 그 목적지로 **native socket** 을 직접 열어 데이터를 펌프질한다(재생성). 포워딩이 아니므로 통신사엔 폰 자체 트래픽으로 보인다. 루팅 불필요.
2. **USB 채널 (adb)** — `adb forward` 로 PC의 `localhost:1080` 을 USB를 통해 폰의 `1080` 포트(=SOCKS 서버)로 터널링한다.
3. **PC (windows/)** — 지정한 프로세스(.exe)의 트래픽만 `SOCKS5 127.0.0.1:1080` 으로 보낸다. 나머지는 기존 회선(`Direct`)을 그대로 쓴다.

핵심: PC엔 인터넷 경로가 **두 개**가 된다. 규칙에 매칭된 프로그램만 폰 회선을, 나머지는 평소 회선을 탄다. (지정 안 한 프로그램이 끊기는 게 아니라, 평소대로 동작한다.)

---

## 구성

모노레포.

```
adb-tether/
├─ android/   # 폰 측 SOCKS5 재생성 서버 앱
├─ windows/   # PC 측 클라이언트 (프로세스별 선택 라우팅 + adb 셋업)
└─ README.md
```

### android/ — SOCKS5 재생성 서버

- `127.0.0.1:1080` 에 SOCKS5 서버를 바인딩 (adb를 통해서만 접근되도록 loopback에만 바인딩).
- SOCKS CONNECT 요청마다 목적지로 새 TCP 소켓을 열고 양방향 릴레이.
- 백그라운드 유지를 위해 **포그라운드 서비스**로 구동 (안드로이드 백그라운드 실행 제한 회피).
- 필요 권한: `INTERNET` (+ 포그라운드 서비스 관련 권한). 루팅·시스템 권한 불필요.
- TODO: UDP ASSOCIATE 지원 여부 결정.

### windows/ — PC 클라이언트

PC의 선택된 프로세스 트래픽을 SOCKS5(`127.0.0.1:1080`)로 라우팅한다.

- 초기 단계: **Proxifier**(상용) 규칙 + adb 셋업 스크립트로 구성.
  - 프로세스(.exe) 단위 규칙으로 무제한 회선에 태울 프로그램만 콕 집어 지정.
  - **`Resolve hostnames through proxy` 반드시 ON** — 안 켜면 DNS 조회가 기존 회선으로 새서(DNS leak) 분리가 깨진다.
- 장기: 자체 클라이언트(프로세스 필터 + SOCKS 라우팅) 직접 구현 가능.
- WSL/리눅스에서 쓸 경우 `proxychains` 또는 `tun2socks` 로 대체.

---

## 요구사항

- 안드로이드 폰 (USB 디버깅 가능), 무제한 데이터 요금제.
- **데이터 전송용** USB 케이블 (충전 전용 X).
- PC에 `adb` (platform-tools).
- 윈도우: Proxifier (또는 자체 클라이언트).

---

## 사용법 (초안)

```bash
# 1. 폰: USB 디버깅 ON, 케이블 연결 후 인식 확인
adb devices

# 2. PC localhost:1080 → 폰 1080(SOCKS 서버)로 USB 터널 생성
#    (reverse 아님 주의: 서버가 폰에 있고 PC가 접속하므로 forward)
adb forward tcp:1080 tcp:1080
```

3. 폰: android 앱 실행 → SOCKS5 서버 시작 (`127.0.0.1:1080`).
4. PC: Proxifier 설정
   - Proxy: `SOCKS5`, `127.0.0.1:1080`
   - Rules: 폰 회선에 태울 프로세스만 → 해당 프록시 / 그 외 → `Direct`
   - `Resolve hostnames through proxy` **ON**
5. 검증: 대상 프로그램에서 외부 IP 조회 → 폰 셀룰러 IP로 나오는지, 통신사 테더링 한도가 차감되지 않는지 확인.

---

## 제약사항 / 주의

- **TCP/UDP만** 깔끔하게 처리. `raw IP`·`ICMP(ping)` 등 소켓 추상화에 안 맞는 트래픽은 이 회선으로 안 간다. (브라우징·API·git·빌드 등 TCP 기반은 문제없음.)
- **DNS leak**: 기본값이면 도메인→IP 조회가 기존 회선으로 샌다. Proxifier의 `Resolve hostnames through proxy` 로 막아야 완전 분리.
- **약관 위반 소지** 및 행위 기반 탐지(대용량 사용량 등)는 회피 못 함.
- userspace 릴레이라 순수 라우팅보다 오버헤드가 있으나, 처리를 PC가 하므로 폰 회선(예: 5Mbps) 정도는 병목 없음. 발열·배터리 소모는 폰 쪽에 발생 → 충전 겸 케이블 연결 권장.
- 개인 용도 전용. 서비스/배포 목적 아님.

---

## 상태

**M1 통과(2026-07-03)** — 재생성 트래픽이 통신사 테더링으로 안 잡힘을 실기기+실과금으로 검증(프록시 1GB → 공유 쿼터 불변, 일반 데이터만 차감). 다음은 M2(android 안정화).
- `android/`: SOCKS5 재생성 서버 **스캐폴딩 완료, 디버그 빌드+실기기 동작 검증됨**(설치·실행·프록시 동작 확인).
- `windows/`: M1은 Proxifier 설정 + `adb forward`(빌드 대상 없음). 검증은 `curl` 차등 비교로 진행(Proxifier 실사용 세팅 L2는 미완). 자체 클라이언트(Phase 2)는 미착수.

진행 계획은 [ROADMAP.md](ROADMAP.md), 검증 결과·절차는 [windows/M1-VALIDATION.md](windows/M1-VALIDATION.md) 참조.

## 문서

- [HANDOFF.md](HANDOFF.md) — **세션 이어받기용** 현황·결정 근거·다음 단계 요약
- [README.md](README.md) — 개요·동작 원리·사용법
- [ROADMAP.md](ROADMAP.md) — 단계별 진행 계획(M1~M4)
- [android/DESIGN.md](android/DESIGN.md) — 폰 SOCKS5 재생성 서버 설계
- [windows/DESIGN.md](windows/DESIGN.md) — PC측 라우팅 클라이언트 설계
- [windows/M1-VALIDATION.md](windows/M1-VALIDATION.md) — M1 실기기 검증 체크리스트(Windows 명령)
