package com.esrrhs.pingtunnel.client.data

import com.esrrhs.pingtunnel.client.vpn.TunConfig
import kotlinx.serialization.Serializable

/** 单条隧道连接的生命周期记录，用于溯源「哪个 App、以什么方式、结果如何」。 */
@Serializable
data class ConnectionLogEntry(
    /** hev 五元组 key：proto|src->dst:port。 */
    val key: String,
    val uid: Int,
    val packageName: String? = null,
    val label: String,
    /** "TCP" / "UDP"。 */
    val proto: String,
    val domain: String? = null,
    val remoteIp: String,
    val remotePort: Int,
    /** 出站方式：[ROUTE_DIRECT] 本地直连 / [ROUTE_PROXY] SPP 隧道 / [ROUTE_UNKNOWN]。 */
    val route: String = ROUTE_UNKNOWN,
    /** 走代理时所属配置名称（故障切换时用于区分不同出口）。 */
    val proxyName: String? = null,
    /** 走代理时所属配置的服务器地址。 */
    val proxyServer: String? = null,
    /** 结果：[RESULT_SUCCESS] / [RESULT_FAILED] / [RESULT_UNKNOWN]（如 UDP 无 CONNECT 结果）。 */
    val result: String = RESULT_UNKNOWN,
    /** 失败原因摘要（SOCKS 拒绝/超时/DNS 失败等）。 */
    val reason: String? = null,
    /** 建连耗时毫秒（来自分流器事件，可能为 null）。 */
    val connectMs: Int? = null,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val startMs: Long,
    /** 结束时刻；0 表示仍在进行。 */
    val endMs: Long = 0,
) {
    /** 目标是否为 IPv6 地址。 */
    val isIpv6: Boolean
        get() = remoteIp.contains(":") || (domain != null && domain.contains(":"))

    /**
     * 是否为 DNS 解析类会话：明文 DNS(53)、DoT(853)，或发往公共解析器/本 VPN
     * 下发 DNS 的 DoH(443)。
     *
     * 这类会话频率极高（系统与应用的加密 DNS 探测每秒数次），混在连接历史里会在
     * 几分钟内把 1000 条上限刷满、挤掉真正的业务连接，因此单独成类、单独存储与展示。
     */
    val isDnsQuery: Boolean
        get() = when {
            remotePort == 53 || remotePort == 853 -> true
            remotePort == 443 && remoteIp in RESOLVER_ENDPOINTS -> true
            else -> false
        }

    companion object {
        const val ROUTE_DIRECT = "direct"
        const val ROUTE_PROXY = "proxy"
        const val ROUTE_UNKNOWN = "unknown"

        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILED = "failed"
        const val RESULT_UNKNOWN = "unknown"

        /**
         * 常见公共解析器 + 本 VPN 下发的回退 DNS。DoH 会话在 SOCKS 请求里只有 IP
         * 没有域名，只能按端点识别；误伤面是"直接访问解析器自己的 443 站点"，可忽略。
         */
        private val RESOLVER_ENDPOINTS = setOf(
            TunConfig.DNS_ADDRESS,
            TunConfig.FALLBACK_DNS,
            "8.8.8.8", "8.8.4.4",           // Google
            "1.1.1.1", "1.0.0.1",           // Cloudflare
            "9.9.9.9", "149.112.112.112",   // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
        )
    }
}
