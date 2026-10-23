package com.esrrhs.pingtunnel.client.util

/**
 * 连接自检结论（纯逻辑，便于单测）。
 *
 * App 自身被 addDisallowedApplication 排除在 VPN 外，因此「直连出口 IP」
 * 与「经隧道出口 IP」在设备上分别可得；两者不同即证明流量确实走了 SPP Server。
 */
enum class TunnelStatus {
    /** 经隧道拿到了与直连不同的出口 IP。 */
    PROXY_OK,

    /** 隧道出口与直连相同（可能未生效/误判，需要排查）。 */
    SAME_IP,

    /** 只有直连成功，隧道请求失败。 */
    PROXY_FAILED,

    /** 两边都失败（无网络或检测服务不可达）。 */
    NETWORK_FAILED,
}

object TunnelVerdict {

    fun of(directIp: String?, proxyIp: String?): TunnelStatus {
        val direct = directIp?.trim().orEmpty()
        val proxy = proxyIp?.trim().orEmpty()
        return when {
            proxy.isNotEmpty() && direct.isNotEmpty() && proxy != direct -> TunnelStatus.PROXY_OK
            proxy.isNotEmpty() && proxy == direct -> TunnelStatus.SAME_IP
            direct.isNotEmpty() -> TunnelStatus.PROXY_FAILED
            else -> TunnelStatus.NETWORK_FAILED
        }
    }
}
