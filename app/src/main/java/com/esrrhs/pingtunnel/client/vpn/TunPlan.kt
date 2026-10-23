package com.esrrhs.pingtunnel.client.vpn

import com.esrrhs.pingtunnel.client.vpn.TunConfig.DNS_ADDRESS
import com.esrrhs.pingtunnel.client.vpn.TunConfig.FALLBACK_DNS
import com.esrrhs.pingtunnel.client.vpn.TunConfig.MTU
import com.esrrhs.pingtunnel.client.vpn.TunConfig.SESSION
import com.esrrhs.pingtunnel.client.vpn.TunConfig.TUN_ADDRESS
import com.esrrhs.pingtunnel.client.vpn.TunConfig.TUN_ADDRESS_V6
import com.esrrhs.pingtunnel.client.vpn.TunConfig.TUN_PREFIX
import com.esrrhs.pingtunnel.client.vpn.TunConfig.TUN_PREFIX_V6

/**
 * `VpnService.Builder` 的参数计划：把「给不给 IPv6、加哪些地址/DNS/路由」这类判定
 * 从 Android Builder 调用里剥出来成为纯数据，方便单测直接锁定（Builder 在 JVM 单测里
 * 无法实例化）。`PingtunnelVpnService.buildTun` 只负责把计划套到 Builder 上。
 */
data class TunPlan(
    val session: String,
    val mtu: Int,
    val addresses: List<TunAddress>,
    val dnsServers: List<String>,
    val routes: List<TunRoute>,
) {

    data class TunAddress(val address: String, val prefixLength: Int)

    data class TunRoute(val address: String, val prefixLength: Int)

    companion object {

        /**
         * 路由恒定为全抓（IPv4 一条、IPv6 一条）：
         *  - 物理上不可能再触发 VpnService 路由表 Binder parcel / Android 15
         *    NetworkMonitor 的 TransactionTooLargeException；
         *  - CN 直连、绕过局域网全部下沉到 RuleSocksServer 用户态精确判定。
         */
        fun of(enableIpv6: Boolean): TunPlan {
            val addresses = mutableListOf(TunAddress(TUN_ADDRESS, TUN_PREFIX))
            val routes = mutableListOf(TunRoute("0.0.0.0", 0))
            if (enableIpv6) {
                addresses += TunAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                routes += TunRoute("::", 0)
            }
            return TunPlan(
                session = SESSION,
                mtu = MTU,
                addresses = addresses,
                // mapdns 虚拟 DNS + 公网兜底：后者满足系统 NetworkMonitor 的 DoT/DNS 探测，
                // 避免私有 DNS 超时把网络判定成"无互联网"
                dnsServers = listOf(DNS_ADDRESS, FALLBACK_DNS),
                routes = routes,
            )
        }
    }
}
