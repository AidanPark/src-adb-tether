# adb-tether 자체 클라이언트 (블랙리스트 라우팅)

**목표**: PC의 모든 트래픽을 기본으로 폰 셀룰러(재생성)로 보내고, **예외 프로그램만** PC 기존 회선으로.
Proxifier 대체(= ROADMAP M4). 엔진은 mihomo(clash.meta), TUN + 프로세스 규칙 + fake-ip(DNS 누수 방지).

## 구성 (이 폴더)
| 파일 | 역할 |
|------|------|
| `run.bat` | 실행 런처(관리자 자동 요청 + adb forward + mihomo 실행) |
| `config.yaml` | 라우팅 규칙(기본→폰, 예외→Direct). 보통 건드릴 일 없음 |
| **`exceptions.yaml`** | **★ 예외 프로그램 목록 — 여기만 편집** |
| `mihomo.exe` / `wintun.dll` | 엔진 + TUN 드라이버 |

## 실행 / 종료
1. 폰: **Every Proxy HTTP `:8080` ON** + USB 연결(USB 디버깅 ON)
   - 자작 SOCKS5 앱(`android/`)을 쓰려면 `config.yaml` 의 socks5 블록으로 교체 + `run.bat` 포트를 1080으로
2. PC: **Windows 수동 프록시 OFF** 확인 → **`run.bat` 더블클릭** → **UAC 허용** → 검은 창에 로그가 흐르면 실행 중
3. 종료: 그 **검은 창을 닫기**(TUN 해제)

전체 절차·검증법은 저장소 루트 `MANUAL.md` 참조.

실행 중엔 그냥 평소처럼 프로그램을 쓰면 됨 — 기본은 폰 회선, 예외 목록 프로그램만 기존 회선.

## 예외 프로그램 추가/관리 (핵심)
`exceptions.yaml` 의 `payload:` 밑에 프로세스명으로 한 줄씩:
```yaml
payload:
  - PROCESS-NAME,OneDrive.exe
  - PROCESS-NAME,steam.exe
  - PROCESS-NAME,Dropbox.exe
```
편집 후 **mihomo 재시작**(창 닫고 `run.bat` 다시)하면 적용.

## 검증 방법
mihomo 실행 중, 콘솔에서:
```
curl.exe https://ifconfig.me/ip
```
- `curl.exe` 가 `exceptions.yaml` 에 있으면 → **PC 기존 회선 IP**
- 없으면(기본) → **폰 셀룰러 IP** (2001:2d8:...)

## 주의 / 한계
- **관리자 권한 + TUN 드라이버** 필수(블랙리스트=전체 캡처의 본질).
- 폰 SOCKS 는 **TCP만**(M1). QUIC/UDP 는 TCP 폴백 또는 차단(M3에서 UDP 확장 예정).
- ⚠️ **볼륨 주의**: 기본이 폰이라 Windows 업데이트·클라우드 동기화 등 배경 트래픽도 폰 데이터를 씀 →
  볼륨 큰 배경 프로그램은 `exceptions.yaml` 로 빼는 걸 권장.
- 이상 시 mihomo 창을 닫으면 즉시 원복.

## 상태
- **Stage 0/1 완료(2026-07-03)**: 블랙리스트 라우팅 + 예외 파일 관리 실기기 검증됨.
  (기본→폰 `2001:2d8:...`, 예외 curl.exe→기존회선 `211.234.198.56` 확인.)
- **폰쪽 서버를 Every Proxy(HTTP `:8080`)로 전환(2026-07-10)**: 자작앱 빌드 없이 즉시 구동.
  `config.yaml` 에 자작 SOCKS5(`:1080`) 블록을 주석으로 보존.
- **WSL 캡처 검증 완료(2026-07-10)**: TUN 가동 시 Windows·WSL 양쪽 모두 재생성 경로(`223.38.72.253`)로 확인.
  raw였다면 테더 APN(`223.38.73.x`)이 나왔을 것 → 시스템 프록시 방식이 못 잡던 WSL 누수 해소.
- **Stage 2(선택)**: 트레이 UI + 단일 exe(엔진 라이브러리 임베드, Go). 원할 때.
