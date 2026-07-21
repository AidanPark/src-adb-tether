# android/ — adb-tether 프록시 앱 (M1)

폰에서 동작하는 SOCKS5 재생성 프록시 서버. 설계는 [DESIGN.md](DESIGN.md) 참조.

## 빌드

Android Studio에서 `android/` 폴더를 열면 Gradle 동기화 후 바로 빌드된다.

CLI로 빌드하려면 Gradle 래퍼 JAR이 필요하다(레포에는 `gradle-wrapper.properties`만 포함).
시스템 Gradle(8.9+)로 한 번 생성한 뒤 래퍼를 쓰면 된다:

```bash
cd android
gradle wrapper --gradle-version 8.10.2   # gradlew/gradle-wrapper.jar 생성(최초 1회)
./gradlew assembleDebug                   # APK 빌드
./gradlew installDebug                    # 연결된 기기에 설치
```

요구: JDK 17, Android SDK (compileSdk 35).

## 실행 (M1 검증 흐름)

1. 앱 설치 후 실행 → **시작** 누름(첫 실행 시 알림 권한 1회 요청). 셀룰러 데이터 ON 상태여야 함.
2. PC에서 USB 연결 후:
   ```
   adb forward tcp:1080 tcp:1080
   ```
3. PC에서 `127.0.0.1:1080` 을 SOCKS5 프록시로 지정(Proxifier 등).
4. 검증: 외부 IP가 폰 셀룰러 IP인지, 통신사 테더링 한도가 차감되지 않는지 확인.

## 구성

| 파일 | 역할 |
|------|------|
| `MainActivity.kt` | 시작/정지 토글 + 상태 표시 |
| `ProxyService.kt` | 포그라운드 서비스(specialUse), 수명주기 |
| `socks/Socks5Server.kt` | 127.0.0.1:1080 accept 루프 |
| `socks/Socks5Connection.kt` | SOCKS5 핸드셰이크 + CONNECT + 릴레이 |
| `net/CellularNetwork.kt` | 셀룰러 Network 확보, 목적지 소켓 재생성 |
| `socks/SocksConfig.kt` | 설정값/상태 모델 |
