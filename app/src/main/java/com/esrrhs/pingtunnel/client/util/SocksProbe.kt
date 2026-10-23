package com.esrrhs.pingtunnel.client.util

import android.util.Log
import com.esrrhs.pingtunnel.client.proxy.Socks5Codec
import java.io.DataInputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocketFactory

/**
 * SOCKS5 探针：经本地 socks5_client 访问外网，用于真实延迟测量与 DNS 触发。
 *
 * - [measure]：端到端实测——SOCKS 建连 + TLS 握手 + HTTP GET 直到收到响应状态行，
 *   而非仅 SOCKS/TCP 握手。默认依次尝试多个目标，首个成功即返回耗时。
 * - [connectOnly]：只做 CONNECT（供 DNS 泄漏探测触发远端解析用）。
 */
object SocksProbe {

    private const val TAG = "SocksProbe"

    const val DEFAULT_TARGET_HOST = "www.google.com"
    const val DEFAULT_TARGET_PORT = 443
    const val DEFAULT_TARGET_PATH = "/generate_204"

    /**
     * 探测目标。
     * @property dst SOCKS CONNECT 的目标：域名（ATYP=域名）或 IPv4 字面量（ATYP=IPv4）
     * @property sni TLS 握手使用的 SNI（与 [httpHost] 可与 IP 目标不同）
     */
    data class Target(
        val dst: String,
        val port: Int = 443,
        val sni: String = dst,
        val httpHost: String = dst,
        val path: String = "/",
    )

    /**
     * 默认目标列表。
     *
     * 首选 IP 字面量（1.1.1.1 的 Cloudflare anycast）：spp 服务端无需做 DNS，
     * 可免疫「明文 DNS 被 GFW 注入污染 A 记录、Go 只拨一条假 IP」导致的测速失败；
     * SNI/Host 用 one.one.one.one（1.1.1.1 证书覆盖该名称），/cdn-cgi/trace 返回 200。
     * 其后保留 Google 域名目标，供服务端在干净网络环境下做跨地域实测。
     */
    val DEFAULT_TARGETS: List<Target> = listOf(
        Target(
            dst = "1.1.1.1",
            sni = "one.one.one.one",
            httpHost = "one.one.one.one",
            path = "/cdn-cgi/trace",
        ),
        Target(
            dst = DEFAULT_TARGET_HOST,
            port = DEFAULT_TARGET_PORT,
            path = DEFAULT_TARGET_PATH,
        ),
    )

    // SOCKS5 报文构造（纯函数，便于单测）

    /** 方法协商：仅支持「无需认证」。 */
    fun greeting(): ByteArray = byteArrayOf(0x05, 0x01, 0x00)

    /** CONNECT 请求，ATYP=0x03（域名）。 */
    fun connectDomain(host: String, port: Int): ByteArray {
        val bytes = host.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..255) { "host length must be 1..255" }
        return byteArrayOf(
            0x05, 0x01, 0x00, 0x03, bytes.size.toByte(),
        ) + bytes + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    /** CONNECT 请求，ATYP=0x01（IPv4 字面量）。 */
    fun connectIPv4(ip: String, port: Int): ByteArray {
        val parts = ip.split('.')
        require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            "invalid IPv4: $ip"
        }
        return byteArrayOf(0x05, 0x01, 0x00, 0x01) +
            parts.map { it.toInt().toByte() }.toByteArray() +
            byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    /**
     * 经隧道真实访问目标：TLS 握手 + HTTP GET，返回端到端耗时（ms）。
     * 依次尝试 [targets]，首个成功即返回（[samples] 为同一目标的采样次数，取最小值）；
     * 全部失败返回 -1。
     */
    fun measure(
        socksPort: Int,
        samples: Int = 2,
        timeoutMs: Int = 8000,
        targets: List<Target> = DEFAULT_TARGETS,
    ): Int = measureAt(LOOPBACK_V4, socksPort, "", "", samples, timeoutMs, targets)

    /** 直接测一台 SOCKS5（可带用户名密码），不经过本机 spp。 */
    fun measureRemote(
        host: String,
        port: Int,
        username: String,
        password: String,
        samples: Int = 1,
        timeoutMs: Int = 8000,
        targets: List<Target> = DEFAULT_TARGETS,
    ): Int = measureAt(host, port, username, password, samples, timeoutMs, targets)

    private fun measureAt(
        socksHost: String,
        socksPort: Int,
        username: String,
        password: String,
        samples: Int,
        timeoutMs: Int,
        targets: List<Target>,
    ): Int {
        for (target in targets) {
            var best = -1
            repeat(samples) {
                val ms = oneHttpSample(socksHost, socksPort, username, password, target, timeoutMs)
                if (ms >= 0 && (best == -1 || ms < best)) best = ms
            }
            if (best >= 0) return best
        }
        return -1
    }

    private fun oneHttpSample(
        socksHost: String,
        socksPort: Int,
        username: String,
        password: String,
        target: Target,
        timeoutMs: Int,
    ): Int = runCatching {
        // 连 SOCKS 端口必须绕过系统 ProxySelector：否则系统级 SOCKS 代理会劫持这次
        // 探测（IPv4 被代转发、IPv6 直接 EOF）。细节见 newDirectSocket。
        connectDirect(socksHost, socksPort, timeoutMs).use { socket ->
            val start = System.nanoTime()
            val out: OutputStream = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            if (!socksConnect(input, out, target.dst, target.port, username, password)) {
                Log.w(TAG, "socks5 CONNECT ${target.dst}:${target.port} rejected")
                return@runCatching -1
            }

            // 在隧道 socket 上做真实 TLS 握手（SNI 与证书校验均按目标域名）
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(socket, target.sni, target.port, true)
                as javax.net.ssl.SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.use {
                it.startHandshake()
                val sslOut = it.getOutputStream()
                val sslIn = DataInputStream(it.getInputStream())
                // 不要发送 Connection: close：部分代理链路（机场节点/中继）会对
                // 携带该头的请求直接 RST，表现为 TLS 握手成功但 HTTP 响应 EOF。
                // 读完状态行后本端自行关闭连接即可。
                sslOut.write(
                    ("GET ${target.path} HTTP/1.1\r\nHost: ${target.httpHost}\r\n" +
                        "User-Agent: curl/8.0\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII),
                )
                sslOut.flush()
                val statusLine = sslIn.readLine()
                Log.i(TAG, "${target.dst} -> $statusLine")
                if (statusLine == null) return@use -1
                val code = Regex("HTTP/\\S+\\s+(\\d{3})").find(statusLine)
                    ?.groupValues?.get(1)
                val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
                val ok = code != null &&
                    (code.startsWith("2") || code == "301" || code == "302")
                if (ok) elapsed else -1
            }
        }
    }.onFailure {
        Log.w(TAG, "probe ${target.dst} failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(-1)

    /** 仅完成 SOCKS5 CONNECT（含远端域名解析），成功返回 true。用于 DNS 探测触发。 */
    fun connectOnly(
        socksPort: Int,
        targetHost: String = DEFAULT_TARGET_HOST,
        targetPort: Int = DEFAULT_TARGET_PORT,
        timeoutMs: Int = 5000,
    ): Boolean = runCatching {
        // 同上：本机 SOCKS 端口探测也必须绕过系统 ProxySelector
        connectDirect(LOOPBACK_V4, socksPort, timeoutMs).use { socket ->
            socksConnect(
                DataInputStream(socket.getInputStream()),
                socket.getOutputStream(),
                targetHost,
                targetPort,
            )
        }
    }.getOrDefault(false)

    /** 完成 SOCKS5 方法协商 + CONNECT；[dst] 为域名或 IPv4 字面量。 */
    private fun socksConnect(
        input: DataInputStream,
        out: OutputStream,
        dst: String,
        port: Int,
        username: String = "",
        password: String = "",
    ): Boolean {
        if (!Socks5Codec.authenticateClient(input, out, username, password)) {
            Log.w(TAG, "socks5 auth rejected")
            return false
        }

        val request = if (IPV4_REGEX.matches(dst)) connectIPv4(dst, port)
        else connectDomain(dst, port)
        out.write(request)
        out.flush()
        if (input.readUnsignedByte() != 0x05) return false
        val reply = input.readUnsignedByte()
        if (reply != 0x00) {
            Log.w(TAG, "socks5 CONNECT reply code=$reply")
            return false
        }
        skipBoundAddress(input)
        return true
    }

    /** 丢弃回复中的 BND.ADDR + BND.PORT。 */
    private fun skipBoundAddress(input: DataInputStream) {
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.readUnsignedByte()
            else -> 0
        }
        repeat(addrLen + 2) { input.readUnsignedByte() } // 地址 + 端口
    }

    private val IPV4_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
}
