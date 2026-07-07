package com.adbtether.socks

import com.adbtether.net.CellularNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 루프백(127.0.0.1:port)에서 SOCKS5 연결을 받는 accept 루프.
 * 연결마다 [Socks5Connection] 코루틴을 띄운다.
 */
class Socks5Server(
    private val cellular: CellularNetwork,
    private val config: SocksConfig,
    private val onStateChanged: (ServerState) -> Unit = {},
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val counters = ProxyCounters()

    fun start() {
        if (running) return
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        // 누적 바이트/상태를 주기적으로 반영. 연결·청크마다 emit 하면 과도하므로 1초 틱.
        // (ServerState 는 data class 라 값이 그대로면 StateFlow 가 중복 방출하지 않음.)
        s.launch {
            while (isActive) {
                delay(1000)
                emit()
            }
        }
        s.launch {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName(config.bindAddress), config.listenPort))
                    serverSocket = ss
                    emit()
                    while (isActive) {
                        val client = ss.accept()
                        counters.active.incrementAndGet()
                        counters.total.incrementAndGet()
                        emit()
                        launch {
                            Socks5Connection(client, cellular, config, counters) {
                                counters.active.decrementAndGet(); emit()
                            }.handle()
                        }
                    }
                }
            } catch (_: Exception) {
                // bind 실패 또는 stop()에 의한 종료
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        counters.active.set(0)
        emit()
    }

    /** 셀룰러 확보 여부가 바뀌면 즉시 상태를 다시 방출한다(알림 '셀룰러 대기/OK'). */
    fun onCellularChanged() = emit()

    private fun emit() = onStateChanged(
        ServerState(
            running = running,
            activeConnections = counters.active.get(),
            totalConnections = counters.total.get(),
            bytesUp = counters.bytesUp.get(),
            bytesDown = counters.bytesDown.get(),
            cellularAvailable = cellular.isAvailable,
        )
    )
}
