package com.esrrhs.pingtunnel.client.util

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 连接自检：分别用直连和经本地 SOCKS5 隧道访问 IP 回显服务，
 * 对比出口 IP；隧道请求使用域名，成功即说明远端 DNS 路径可用。
 *
 * 注意：App 自身被排除在 VPN 之外，[direct] 取到的是设备真实出口。
 */
object TunnelCheck {

    data class Result(
        val directIp: String?,
        val proxyIp: String?,
        /** 经隧道的建连+首字节耗时（ms）。 */
        val latencyMs: Int,
        val status: TunnelStatus,
        /** 经域名经隧道访问成功：远端解析路径可用（非严格的 DNS 泄漏证明）。 */
        val dnsViaProxy: Boolean,
    )

    // 纯文本回显 IP 的服务，按序回退
    private val ECHO_URLS = listOf(
        "https://ifconfig.me/ip",
        "https://api.ipify.org/",
    )

    fun run(socksPort: Int, timeoutMs: Int = 8000): Result {
        val direct = runCatching { fetchVia(Proxy.NO_PROXY, timeoutMs) }.getOrNull()
        var latencyMs = -1
        var proxy: String? = null
        runCatching {
            val proxySocks = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", socksPort),
            )
            val start = System.nanoTime()
            proxy = fetchVia(proxySocks, timeoutMs)
            latencyMs = ((System.nanoTime() - start) / 1_000_000).toInt()
        }
        val status = TunnelVerdict.of(direct, proxy)
        return Result(
            directIp = direct?.trim(),
            proxyIp = proxy?.trim(),
            latencyMs = latencyMs,
            status = status,
            dnsViaProxy = !proxy.isNullOrBlank(),
        )
    }

    private fun fetchVia(proxy: Proxy, timeoutMs: Int): String? {
        for (urlText in ECHO_URLS) {
            val conn = (URL(urlText).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "curl/8.0")
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (body.isNotEmpty()) return body
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
