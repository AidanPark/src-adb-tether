package com.adbtether.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * 셀룰러(이동통신) Network 를 확보·유지한다.
 *
 * 핵심: 폰에 WiFi 가 켜져 있어도 목적지 소켓과 DNS 가 **셀룰러로 나가도록 강제**한다.
 * 그렇지 않으면 재생성한 트래픽이 폰 WiFi 로 나가서 무제한 셀룰러 데이터를 쓰지 않게 된다.
 */
class CellularNetwork(
    context: Context,
    /** 셀룰러 확보/유실 시 즉시 호출(알림·UI를 바로 갱신하기 위함). */
    private val onChanged: () -> Unit = {},
) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val current = AtomicReference<Network?>(null)
    private var callback: ConnectivityManager.NetworkCallback? = null

    val isAvailable: Boolean get() = current.get() != null

    /** 셀룰러 Network 요청을 등록해 라디오를 깨워 유지한다. */
    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current.set(network)
                onChanged()
            }

            override fun onLost(network: Network) {
                if (current.compareAndSet(network, null)) onChanged()
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        current.set(null)
    }

    /**
     * 셀룰러 Network 로 목적지 소켓을 연다(재생성의 핵심).
     * DNS 해석도 동일 Network 로 수행해 누수를 막는다.
     */
    fun openSocket(host: String, port: Int, connectTimeoutMs: Int): Socket {
        val net = current.get() ?: error("cellular network not available")
        val address = net.getByName(host) ?: error("cannot resolve $host")
        val socket = net.socketFactory.createSocket()
        socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
        return socket
    }
}
