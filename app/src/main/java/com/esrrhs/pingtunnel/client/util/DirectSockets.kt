package com.esrrhs.pingtunnel.client.util

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI

/**
 * 新建一个**保证不经过系统代理**的 TCP socket。
 *
 * `java.net.Socket()` 会查询进程默认 `ProxySelector`（macOS/Windows 上即系统代理设置）。
 * 开发机或用户系统若配了系统级 SOCKS 代理（如 Clash 的 127.0.0.1:7890），本 App 自己的
 * 出站连接会被静默劫持进该代理：
 *  - 连本地 SOCKS 端口（127.0.0.1:x）的探测被代转发，IPv4 目标"看似成功"；
 *  - 连 IPv6 目标（如 ::1）被代理拒发，握手完成后连接直接 EOF，无任何异常。
 *
 * 代理服务自身的出站连接（直连目标 / 上游 SOCKS）与本地端口探测都必须显式绕过，
 * 否则分流直连在配了系统代理的机器上整体失效（仅 IPv4 碰巧可用）。
 *
 * **排查同类问题时注意**：只有「未指定 proxy」的出站才会查 [ProxySelector]，
 * 以下写法天然不受系统代理影响，不要"顺手修"它们：
 *  - `Socket(Proxy)` / `Socket(Proxy.NO_PROXY)`（本文件的 helper 即走这条）；
 *  - `ServerSocket(...)`、`DatagramSocket(...)`（JDK 不查 ProxySelector）；
 *  - `URL.openConnection(proxy)` 显式传参的 HTTP（见 LeakCheck / IpQuery / TunnelCheck）。
 */
internal fun newDirectSocket(): Socket = Socket(Proxy.NO_PROXY)

/** 建立一条绕过默认 [ProxySelector] 的 TCP 连接（已置 tcpNoDelay）。 */
internal fun connectDirect(host: String, port: Int, timeoutMs: Int): Socket =
    newDirectSocket().apply {
        tcpNoDelay = true
        soTimeout = timeoutMs
        connect(InetSocketAddress(host, port), timeoutMs)
    }

/**
 * 探测本机端口是否已在 listen。
 *
 * 必须绕过 [ProxySelector]（理由见 [newDirectSocket]），且显式用 IPv4 回环：Android 的
 * `InetAddress.getLoopbackAddress()` 返回 `::1`，而 spp 的 `-fromaddr` 绑定的是 127.0.0.1。
 */
internal fun isLoopbackListening(port: Int, timeoutMs: Int = 250): Boolean = runCatching {
    newDirectSocket().use {
        it.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK_V4), port), timeoutMs)
    }
    true
}.getOrDefault(false)

/** IPv4 回环字面量：与 spp `-fromaddr` 的绑定域保持一致。 */
internal const val LOOPBACK_V4 = "127.0.0.1"

/**
 * 进程级兜底：把默认 [ProxySelector] 换成「回环一律直连，其余沿用原默认」。
 *
 * 逐点改 [newDirectSocket] 只能护住已知调用点；这里把整类风险一次性封死——
 * 以后任何新写的 `Socket()` / 无参 `URL.openConnection()` 连本机端口都不会
 * 再被系统级 SOCKS 代理劫持。两条防线并存，互为兜底。
 *
 * 只在 Application.onCreate 安装一次（幂等）。
 */
object DirectProxySelector {

    /**
     * 安装（幂等）：当前默认 selector 已是 [LoopbackNoProxySelector] 时直接返回，
     * 避免多处调用把链越套越深。
     */
    @Synchronized
    fun install() {
        val current = ProxySelector.getDefault()
        if (current is LoopbackNoProxySelector) return
        ProxySelector.setDefault(LoopbackNoProxySelector(current))
    }
}

/** 回环目标返回 [Proxy.NO_PROXY]，其余委托给安装前的默认 selector。 */
internal class LoopbackNoProxySelector(
    private val delegate: ProxySelector?,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        if (isLoopbackHost(uri?.host)) return listOf(Proxy.NO_PROXY)
        val chosen = delegate?.select(uri)
        // 无默认 selector（或它放弃决策）时必须回 NO_PROXY，否则调用方拿到空列表会直接失败
        return if (chosen.isNullOrEmpty()) listOf(Proxy.NO_PROXY) else chosen
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegate?.connectFailed(uri, sa, ioe)
    }
}

/**
 * 是否为回环地址字面量。
 *
 * `Socket()` 无参构造连本机时，JDK 用 `socket://host:port` 合成 URI 查 selector，
 * host 可能是 `127.x.x.x`、`::1` 或 `localhost`，这里一并覆盖。
 */
internal fun isLoopbackHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val h = host.trim().trim('[', ']') // URI.host 通常不带方括号，双保险
    if (h.equals("localhost", ignoreCase = true)) return true
    if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
    val parts = h.split('.')
    // 127.0.0.0/8 整段都是回环
    return parts.size == 4 && parts[0] == "127" && parts.all { it.toIntOrNull() in 0..255 }
}
