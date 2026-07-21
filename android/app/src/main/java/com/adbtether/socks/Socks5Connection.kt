package com.adbtether.socks

import com.adbtether.net.CellularNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS5 연결 1건 처리: 인증 협상 → CONNECT 파싱 → 셀룰러로 목적지 재생성 → 양방향 릴레이.
 *
 * 범위(M1): NO-AUTH + CONNECT(TCP) 만. BIND/UDP ASSOCIATE 미지원.
 */
class Socks5Connection(
    private val client: Socket,
    private val cellular: CellularNetwork,
    private val config: SocksConfig,
    private val counters: ProxyCounters,
    private val onClosed: () -> Unit,
) {
    suspend fun handle() {
        var remote: Socket? = null
        try {
            withContext(Dispatchers.IO) {
                client.tcpNoDelay = true
                // 핸드셰이크는 바이트 단위라 별도 버퍼링 없이 읽는다(릴레이 시 read-ahead 손실 방지).
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()

                if (!negotiate(input, output)) return@withContext
                val target = readConnectRequest(input, output) ?: return@withContext

                // 셀룰러 미확보 시 재생성 불가 → 신규 연결을 명확히 거부(WiFi 로 새지 않게).
                if (!cellular.isAvailable) {
                    reply(output, REP_NETWORK_UNREACHABLE)
                    return@withContext
                }

                val r = try {
                    cellular.openSocket(target.first, target.second, config.connectTimeoutMs)
                } catch (e: Exception) {
                    reply(output, REP_HOST_UNREACHABLE)
                    return@withContext
                }
                remote = r
                r.tcpNoDelay = true
                reply(output, REP_SUCCESS)
                relay(client, r)
            }
        } catch (_: IOException) {
            // 연결 끊김/정상 종료
        } finally {
            closeQuietly(remote)
            closeQuietly(client)
            onClosed()
        }
    }

    /** 메서드 협상: NO-AUTH(0x00) 만 수용. */
    private fun negotiate(input: DataInputStream, output: OutputStream): Boolean {
        if (input.readUnsignedByte() != VERSION) return false
        val count = input.readUnsignedByte()
        val methods = ByteArray(count)
        input.readFully(methods)
        return if (methods.contains(METHOD_NO_AUTH)) {
            output.write(byteArrayOf(VERSION.toByte(), METHOD_NO_AUTH)); output.flush(); true
        } else {
            output.write(byteArrayOf(VERSION.toByte(), METHOD_NONE_ACCEPTABLE)); output.flush(); false
        }
    }

    /** CONNECT 요청 파싱. 성공 시 (host, port), 실패 시 에러 응답 후 null. */
    private fun readConnectRequest(input: DataInputStream, output: OutputStream): Pair<String, Int>? {
        if (input.readUnsignedByte() != VERSION) return null
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        if (cmd != CMD_CONNECT) {
            reply(output, REP_CMD_NOT_SUPPORTED); return null
        }
        val host = when (atyp) {
            ATYP_IPV4 -> readIp(input, 4)
            ATYP_DOMAIN -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len); input.readFully(b)
                String(b, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> readIp(input, 16)
            else -> {
                reply(output, REP_ATYP_NOT_SUPPORTED); return null
            }
        } ?: run { reply(output, REP_GENERAL_FAILURE); return null }
        val port = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        return host to port
    }

    private fun readIp(input: DataInputStream, n: Int): String? {
        val b = ByteArray(n); input.readFully(b)
        return InetAddress.getByAddress(b).hostAddress
    }

    /** 양방향 바이트 릴레이. 한쪽이 끝나면 반대쪽도 half-close 로 EOF 전파. */
    private suspend fun relay(a: Socket, b: Socket) = coroutineScope {
        val up = launch(Dispatchers.IO) { pump(a, b, counters.bytesUp) }     // client → 목적지
        val down = launch(Dispatchers.IO) { pump(b, a, counters.bytesDown) } // 목적지 → client
        up.join(); down.join()
    }

    private fun pump(from: Socket, to: Socket, counter: AtomicLong) {
        val buf = ByteArray(config.bufferSize)
        try {
            val input: InputStream = from.getInputStream()
            val output: OutputStream = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
        }
    }

    /** SOCKS5 응답: VER REP RSV ATYP(IPv4) BND.ADDR(0.0.0.0) BND.PORT(0). */
    private fun reply(output: OutputStream, rep: Byte) {
        output.write(byteArrayOf(VERSION.toByte(), rep, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun closeQuietly(s: Socket?) {
        runCatching { s?.close() }
    }

    companion object {
        private const val VERSION = 0x05
        private const val METHOD_NO_AUTH: Byte = 0x00
        private const val METHOD_NONE_ACCEPTABLE: Byte = 0xFF.toByte()
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
        private const val REP_SUCCESS: Byte = 0x00
        private const val REP_GENERAL_FAILURE: Byte = 0x01
        private const val REP_NETWORK_UNREACHABLE: Byte = 0x03
        private const val REP_HOST_UNREACHABLE: Byte = 0x04
        private const val REP_CMD_NOT_SUPPORTED: Byte = 0x07
        private const val REP_ATYP_NOT_SUPPORTED: Byte = 0x08
    }
}
