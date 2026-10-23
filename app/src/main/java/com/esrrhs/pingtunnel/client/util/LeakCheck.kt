package com.esrrhs.pingtunnel.client.util

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 防泄漏专项检测（App 自身被排除在 VPN 外，直连请求取到的是真实物理出口）：
 *
 * - IPv4：直连/隧道出口对比（同 [TunnelCheck]）；
 * - IPv6：访问仅 AAAA 的回显服务，验证隧道与直连各自是否存在 IPv6 出口；
 * - DNS：经隧道用域名 SOCKS CONNECT 触发 bash.ws 探测域名（远端解析），
 *   再拉取检测到的解析器列表；解析器非空说明 DNS 由 SPP Server 端完成。
 */
object LeakCheck {

    data class Report(
        val ipv4Direct: String?,
        val ipv4Proxy: String?,
        val ipv6Direct: String?,
        val ipv6Proxy: String?,
        val dnsResolvers: List<DnsResolver>,
        val dnsTestFailed: Boolean,
    )

    private const val DNS_TRIGGER_COUNT = 6

    fun run(socksPort: Int, timeoutMs: Int = 6000): Report {
        // IPv4 复用既有检测
        val v4 = TunnelCheck.run(socksPort, timeoutMs)

        val v6Proxy = runCatching {
            httpGet("https://api64.ipify.org/", Proxy(
                Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort),
            ), timeoutMs)
        }.getOrNull()
        val v6Direct = runCatching {
            httpGet("https://api64.ipify.org/", Proxy.NO_PROXY, timeoutMs)
        }.getOrNull()

        var dnsFailed = false
        var resolvers: List<DnsResolver> = emptyList()
        try {
            val id = httpGet("https://bash.ws/id", Proxy(
                Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort),
            ), timeoutMs)?.trim()
            if (!id.isNullOrBlank()) {
                // 让 SPP Server 端解析这些探测域名，bash.ws 记录其解析器
                repeat(DNS_TRIGGER_COUNT) { i ->
                    SocksProbe.connectOnly(socksPort, "${i + 1}.$id.dns.bash.ws", 443)
                }
                val json = httpGet(
                    "https://bash.ws/dnsleak/test/$id?json",
                    Proxy(
                        Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort),
                    ),
                    timeoutMs,
                )
                resolvers = if (json == null) emptyList() else DnsLeakResults.parse(json)
                dnsFailed = json == null
            } else {
                dnsFailed = true
            }
        } catch (e: Exception) {
            dnsFailed = true
        }

        return Report(
            ipv4Direct = v4.directIp,
            ipv4Proxy = v4.proxyIp,
            ipv6Direct = v6Direct?.trim(),
            ipv6Proxy = v6Proxy?.trim(),
            dnsResolvers = resolvers,
            dnsTestFailed = dnsFailed,
        )
    }

    private fun httpGet(urlText: String, proxy: Proxy, timeoutMs: Int): String? {
        val conn = (URL(urlText).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "curl/8.0")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }.trim().ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }
}
