// ProxyServiceV2.kt – foreground service with UDP associate
package com.aidan

import android.app.*
import android.content.*
import android.net.wifi.p2p.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class ProxyServiceV2 : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var cellularNetwork: CellularNetwork
    private lateinit var udpHandler: UdpAssociateHandler

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        cellularNetwork = CellularNetwork(applicationContext)
        // Start TCP SOCKS5 server (same as original)
        scope.launch { SOCKS5ServerV2.start(1080, cellularNetwork) }
        // Start UDP associate handler
        udpHandler = UdpAssociateHandler(cellularNetwork)
        scope.launch { udpHandler.start() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        udpHandler.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channel = "proxy_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nc = NotificationChannel(channel, "Proxy Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(nc)
        }
        return NotificationCompat.Builder(this, channel)
            .setContentTitle("Parsec‑Ready SOCKS5 + UDP")
            .setContentText("Running on Wi‑Fi Direct – 127.0.0.1:1080")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object { private const val NOTIF_ID = 1 }
}
