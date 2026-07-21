package com.adbtether.socks

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 프록시 서버 설정값. M1에서는 기본값 고정, M2에서 설정 UI로 노출 예정. */
data class SocksConfig(
    val bindAddress: String = "127.0.0.1", // 루프백 전용(외부 노출 금지). adb forward 대상
    val listenPort: Int = 1080,
    val connectTimeoutMs: Int = 10_000,
    val bufferSize: Int = 32 * 1024,
)

/** UI/알림에 노출되는 서버 상태 스냅샷. */
data class ServerState(
    val running: Boolean,
    val activeConnections: Int,
    val totalConnections: Long = 0,
    val bytesUp: Long = 0,   // client → 목적지 (업로드)
    val bytesDown: Long = 0, // 목적지 → client (다운로드)
    val cellularAvailable: Boolean = false,
)

/**
 * 서버 생애 동안 누적되는 카운터. 여러 연결 코루틴이 동시에 갱신하므로 atomic.
 * [Socks5Server]가 소유하고 [Socks5Connection]이 갱신한다.
 */
class ProxyCounters {
    val active = AtomicInteger(0)
    val total = AtomicLong(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
}

/** 바이트를 사람이 읽는 단위로. UI·알림 공용. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
