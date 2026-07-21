# android/ — SOCKS5 재생성 프록시 서버 (설계)

폰에서 동작하는 SOCKS5 프록시 서버 앱. PC(Proxifier)가 USB(adb)를 통해 이 서버에 접속하면,
서버는 각 요청 목적지로 **새 소켓을 직접 열어(재생성)** 데이터를 중계한다. 새 소켓은 **셀룰러
네트워크에 강제 바인딩**되므로, 통신사 망에는 폰 자체 앱이 만든 트래픽만 도착한다.

---

## 1. 기술 스택

| 항목 | 값 |
|------|-----|
| 언어 | Kotlin |
| targetSdk | 35 (Android 15) |
| minSdk | 26 (Android 8.0) — 조정 가능 |
| 빌드 | Gradle (Kotlin DSL), 단일 모듈 `:app` |
| 동시성 | kotlinx.coroutines (`Dispatchers.IO`, 연결당 코루틴) |
| SOCKS5 | 외부 라이브러리 없이 직접 구현 (프로토콜이 단순하고 전 구간 통제가 목적) |
| UI | 최소한의 Compose 또는 단일 Activity + 버튼 |

라이브러리 의존성은 최소화한다. 핵심은 `java.net.Socket` + `ConnectivityManager`.

---

## 2. 아키텍처

```
MainActivity ──(start/stop)──▶ ProxyService (Foreground Service)
                                   │
                                   ├─ CellularNetwork (셀룰러 Network 획득·유지)
                                   │
                                   └─ Socks5Server  (127.0.0.1:1080 accept loop)
                                          │  연결마다
                                          ▼
                                     Socks5Connection
                                       1) SOCKS5 handshake
                                       2) CONNECT 파싱
                                       3) 셀룰러로 목적지 소켓 open (재생성)
                                       4) 양방향 릴레이
```

### 컴포넌트

- **`ProxyService`** — 포그라운드 서비스. 서버 수명주기 + 상태 알림 보유. 화면이 꺼져도 유지.
- **`Socks5Server`** — `127.0.0.1:1080` 에 `ServerSocket` 바인딩(루프백 전용), accept 루프. 연결마다 `Socks5Connection` 코루틴 기동.
- **`Socks5Connection`** — SOCKS5 핸드셰이크 → CONNECT 처리 → 릴레이.
- **`CellularNetwork`** — `ConnectivityManager.requestNetwork()` 로 셀룰러 `Network` 를 잡아 유지. 목적지 소켓/DNS는 이 `Network` 로만 나간다.
- **`MainActivity` (+ViewModel)** — 시작/정지 토글, 상태(실행중·포트·활성 연결 수) 표시.

---

## 3. SOCKS5 프로토콜 범위

PC측 Proxifier가 `127.0.0.1:1080` 에 SOCKS5로 접속한다. 구현 범위:

| 단계 | 처리 |
|------|------|
| 인증 협상 | `NO AUTH (0x00)` 만 지원. 루프백 + USB 전용이라 인증 불필요. |
| CONNECT (0x01) | **지원** — TCP 연결 재생성. |
| ATYP=IPv4(0x01) / DOMAIN(0x03) / IPv6(0x04) | 모두 처리. DOMAIN이면 폰이 직접 해석(아래 4 참조) → DNS leak 방지. |
| BIND (0x02) | 미지원 (거절). |
| UDP ASSOCIATE (0x03) | **1차 미지원**, 추후(7 참조). |

> Proxifier에서 `Resolve hostnames through proxy` 를 켜면 ATYP=DOMAIN 으로 들어온다.
> 이때 폰이 셀룰러로 직접 해석해야 PC측 DNS leak이 사라진다.

---

## 4. 셀룰러 네트워크 강제 바인딩 (핵심)

이 프로젝트의 목적은 **셀룰러(무제한) 데이터**를 쓰는 것이다. 폰에 WiFi가 켜져 있으면
기본 네트워크가 WiFi라, 그냥 `Socket(host, port)` 를 열면 WiFi로 나가서 의미가 없다.
따라서 목적지 소켓과 DNS 조회를 **명시적으로 셀룰러 Network에 바인딩**한다.

```kotlin
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()

cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { cellularNetwork = network }
})

// 목적지 연결 (재생성) — 반드시 셀룰러 Network 의 소켓 팩토리 사용
val socket = cellularNetwork.socketFactory.createSocket(host, port)

// DNS 조회도 셀룰러로
val addrs = cellularNetwork.getAllByName(host)
```

- `requestNetwork` 로 셀룰러를 활성 유지(WiFi가 있어도 셀룰러 라디오를 깨워 둠).
- 서버는 셀룰러 `Network` 가 확보되기 전에는 CONNECT를 거절하거나 대기.
- 셀룰러 유실 시 콜백으로 감지 → 신규 연결 차단, 알림 갱신.

---

## 5. 포그라운드 서비스 & 권한

targetSdk 35에서는 포그라운드 서비스 타입 지정이 필수.

- **FGS 타입: `specialUse`** 권장.
  - `dataSync` 는 Android 15에서 1일 사용시간이 제한되므로 상시 프록시에 부적합.
  - `connectedDevice`(USB 연결 기기) 도 의미상 후보지만 부가 권한 요건이 있어 `specialUse` 가 단순.
  - 개인 사이드로드 앱이므로 Play 정책(특수목적 정당화 심사) 부담 없음.

### AndroidManifest 권한

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE"/> <!-- requestNetwork -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/> <!-- API 33+ -->
```

```xml
<service
    android:name=".ProxyService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="usb_socks_proxy_for_personal_tethering"/>
</service>
```

- `POST_NOTIFICATIONS` 는 런타임 요청(Android 13+).
- 배터리 최적화 예외(선택) — 장시간 안정 구동 시 사용자가 수동 허용.

---

## 6. UI (최소)

- 단일 화면: **시작/정지 토글** 1개.
- 상태 표시: 실행 여부 / 리슨 포트 / 셀룰러 확보 여부 / 현재 활성 연결 수 / 누적 송수신 바이트.
- (선택) 최근 연결 로그(목적지 host:port) — 디버깅용.
- 알림(FGS): "프록시 실행 중 — 활성 연결 N".

---

## 7. 설정값

| 키 | 기본값 | 설명 |
|----|--------|------|
| `listenAddress` | `127.0.0.1` | 루프백 전용(외부 노출 금지) |
| `listenPort` | `1080` | adb forward 대상 포트 |
| `forceCellular` | `true` | 목적지 소켓을 셀룰러에 바인딩 |
| `connectTimeoutMs` | `10000` | 목적지 연결 타임아웃 |
| `bufferSize` | `32 KiB` | 릴레이 버퍼 |

---

## 8. 프로젝트 구조 (예정)

```
android/
├─ build.gradle.kts          # 루트
├─ settings.gradle.kts
├─ gradle/ , gradlew, ...
└─ app/
   ├─ build.gradle.kts       # targetSdk 35, minSdk 26, kotlin, coroutines
   └─ src/main/
      ├─ AndroidManifest.xml
      └─ kotlin/<pkg>/
         ├─ MainActivity.kt
         ├─ ProxyService.kt
         ├─ socks/Socks5Server.kt
         ├─ socks/Socks5Connection.kt
         ├─ net/CellularNetwork.kt
         └─ ui/ProxyViewModel.kt
```

---

## 9. 동작 시퀀스 (CONNECT 1건)

1. PC Proxifier → `adb forward` USB 터널 → 폰 `127.0.0.1:1080` 접속.
2. `Socks5Connection`: 인증 협상(NO AUTH) → CONNECT + (host/IP, port) 수신.
3. `CellularNetwork` 에서 셀룰러 `Network` 확보 확인 (없으면 실패 응답 0x03/0x04).
4. 셀룰러로 host 해석(DOMAIN인 경우) → `cellularNetwork.socketFactory` 로 목적지 소켓 open.
5. SOCKS5 성공 응답(0x00) 전송.
6. 두 소켓 간 양방향 바이트 릴레이(코루틴 2개 또는 1개+역방향). EOF/에러 시 양쪽 정리.

---

## 10. 미해결 / 추후

- **UDP ASSOCIATE 미지원의 영향** — 크롬 등은 **QUIC(HTTP/3, UDP 443)** 를 많이 쓴다. UDP를 막으면
  앱이 TCP로 폴백하거나(대개 동작), 일부는 실패한다. 1차는 TCP만으로 검증하고, 필요 시 UDP ASSOCIATE 추가.
- **DNS over UDP** — DOMAIN ATYP로 받으면 폰이 TCP-레벨에서 해석하므로 1차 범위로 충분. UDP DNS 별도 처리는 불필요.
- 연결 폭주 시 코루틴/소켓 상한, 백프레셔.
- 셀룰러 라디오 상시 유지에 따른 배터리 — 충전 겸 연결 권장.
- `ICMP`/raw IP는 SOCKS 추상화 밖이라 처리 불가(설계상 범위 외).
