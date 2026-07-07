package com.adbtether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adbtether.net.CellularNetwork
import com.adbtether.socks.ServerState
import com.adbtether.socks.Socks5Server
import com.adbtether.socks.SocksConfig
import com.adbtether.socks.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 프록시 수명주기를 보유하는 포그라운드 서비스(specialUse).
 * 화면이 꺼져도 SOCKS5 서버 + 셀룰러 Network 를 유지한다.
 */
class ProxyService : Service() {

    private val config = SocksConfig()
    private lateinit var cellular: CellularNetwork
    private lateinit var server: Socks5Server
    private var lastNotified: ServerState? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 셀룰러 확보/유실 시 즉시 상태를 다시 방출해 알림·UI 를 갱신.
        cellular = CellularNetwork(this) { server.onCellularChanged() }
        server = Socks5Server(cellular, config) { st ->
            state.value = st
            // 상태가 실제로 바뀔 때만 알림 갱신(1초 틱마다 재게시 방지).
            if (st.running && st != lastNotified) {
                lastNotified = st
                pushNotification(st)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        cellular.start()
        server.start()
        setEnabled(true) // 재부팅 자동 시작을 위해 '켜짐' 의도 기록
        return START_STICKY
    }

    private fun shutdown() {
        setEnabled(false) // 사용자가 정지 → 재부팅해도 자동 시작 안 함
        server.stop()
        cellular.stop()
        state.value = ServerState(running = false, activeConnections = 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun onDestroy() {
        server.stop()
        cellular.stop()
        state.value = ServerState(running = false, activeConnections = 0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val n = buildNotification(ServerState(running = true, activeConnections = 0))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun pushNotification(st: ServerState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(st))
    }

    private fun buildNotification(st: ServerState): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cellularState = if (st.cellularAvailable) "셀룰러 OK" else "셀룰러 대기"
        val text = "연결 ${st.activeConnections} (누적 ${st.totalConnections}) · " +
            "↑${formatBytes(st.bytesUp)} ↓${formatBytes(st.bytesDown)} · $cellularState"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("adb-tether 프록시 실행 중")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(0, "정지", stopIntent)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Proxy", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        /** UI가 구독하는 상태 스트림(바인딩 없이 상태 공유). */
        val state = MutableStateFlow(ServerState(running = false, activeConnections = 0))

        private const val ACTION_STOP = "com.adbtether.action.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIF_ID = 1

        /** 재부팅 자동 시작 여부(마지막 사용자 의도)를 담는 저장소. [BootReceiver]가 참조. */
        const val PREFS = "adbtether"
        const val KEY_ENABLED = "enabled"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProxyService::class.java).apply { action = ACTION_STOP })
        }
    }
}
