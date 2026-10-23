package com.esrrhs.pingtunnel.client.util

/**
 * 用户态分流的「直连 vs 代理」判定，两处共用同一份决策，避免展示与真实路径不一致：
 *  - proxy/RuleSocksServer：据此决定每条 TCP/UDP 会话本地直发还是转发上游；
 *  - util/ActiveConnections：据此在「当前连接/历史」里标记直连。
 *
 * @param directDomains 命中即直连的域名集合（内置大陆域名表 + 用户自定义）
 * @param directIpv4Cidrs 命中即直连的 IPv4 CIDR（精确 chnroute，无块扩展）
 * @param directIpv6Cidrs 命中即直连的 IPv6 CIDR
 * @param bypassPrivate 额外直连私有/保留段（RFC1918、CGNAT、ULA 等）
 */
class DirectClassifier(
    private val directDomains: Set<String> = emptySet(),
    directIpv4Cidrs: List<String> = emptyList(),
    directIpv6Cidrs: List<String> = emptyList(),
    bypassPrivate: Boolean = false,
) {
    private val ipv4 = IpRouteMatcher(
        buildList {
            if (bypassPrivate) addAll(PRIVATE_V4)
            addAll(directIpv4Cidrs)
        },
    )
    private val ipv6 = IpRouteMatcher(
        buildList {
            if (bypassPrivate) addAll(PRIVATE_V6)
            addAll(directIpv6Cidrs)
        },
    )

    /** 域名（含子域名）是否直连。 */
    fun isDirectDomain(host: String?): Boolean =
        DomainRuleMatcher.matches(host, directDomains)

    /** IP 字面量（v4/v6）是否命中直连 CIDR/私有段；非法文本返回 false。 */
    fun isDirectIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return if (ip.contains(':')) ipv6.contains(ip) else ipv4.contains(ip)
    }

    /**
     * hev 会话的直连判定：有 mapped-DNS 反查域名时按域名（mapdns 场景域名才是
     * 真实目标）；无域名（App 自带 DoH/硬编码 IP）时按真实目标 IP。
     * TCP/UDP 判定一致——UDP 域名/CN 目标同样本地直发。
     */
    fun isDirectSession(domain: String?, remoteIp: String?): Boolean =
        if (!domain.isNullOrBlank()) isDirectDomain(domain) else isDirectIp(remoteIp)

    /** 日志/诊断用的区间规模。 */
    fun summary(): String =
        "domains=${directDomains.size}, v4=${ipv4.rangeCount}, v6=${ipv6.rangeCount}"

    companion object {
        /** 全代理（无任何直连规则）。 */
        val EMPTY = DirectClassifier()

        /** bypass LAN 时直连的 IPv4 私有/保留段（含运营商 CGNAT 100.64.0.0/10）。 */
        val PRIVATE_V4 = listOf(
            "0.0.0.0/8",       // "this" network
            "10.0.0.0/8",      // RFC1918
            "100.64.0.0/10",   // RFC6598 CGNAT（运营商专网/营业厅/IPTV）
            "127.0.0.0/8",     // loopback
            "169.254.0.0/16",  // link-local
            "172.16.0.0/12",   // RFC1918
            "192.168.0.0/16",  // RFC1918
            "224.0.0.0/4",     // multicast（SSDP/mDNS）
            "240.0.0.0/4",     // reserved
        )

        /** bypass LAN 时直连的 IPv6 私有/保留段。 */
        val PRIVATE_V6 = listOf(
            "::/128",          // unspecified
            "::1/128",         // loopback
            "fc00::/7",        // ULA
            "fe80::/10",       // link-local
            "ff00::/8",        // multicast
        )
    }
}
