package com.esrrhs.pingtunnel.client.data

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

    companion object {
        const val ROUTE_DIRECT = "direct"
        const val ROUTE_PROXY = "proxy"
        const val ROUTE_UNKNOWN = "unknown"

        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILED = "failed"
        const val RESULT_UNKNOWN = "unknown"
    }
}
