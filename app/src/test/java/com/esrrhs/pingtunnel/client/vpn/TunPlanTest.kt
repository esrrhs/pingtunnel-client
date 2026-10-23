package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TUN 参数计划的单测：`VpnService.Builder` 无法在 JVM 单测里实例化，
 * 判定逻辑抽成 [TunPlan] 后这里锁定「IPv6 开关」这条最容易出错的分支。
 */
class TunPlanTest {

    @Test
    fun of_ipv6Disabled_onlyV4AddressAndDefaultRoute() {
        val plan = TunPlan.of(false)

        assertEquals(TunConfig.SESSION, plan.session)
        assertEquals(TunConfig.MTU, plan.mtu)
        assertEquals(
            listOf(TunPlan.TunAddress(TunConfig.TUN_ADDRESS, TunConfig.TUN_PREFIX)),
            plan.addresses,
        )
        assertEquals(listOf(TunPlan.TunRoute("0.0.0.0", 0)), plan.routes)
    }

    @Test
    fun of_ipv6Enabled_addsV6AddressAndDefaultRoute() {
        val plan = TunPlan.of(true)

        assertEquals(
            listOf(
                TunPlan.TunAddress(TunConfig.TUN_ADDRESS, TunConfig.TUN_PREFIX),
                TunPlan.TunAddress(TunConfig.TUN_ADDRESS_V6, TunConfig.TUN_PREFIX_V6),
            ),
            plan.addresses,
        )
        assertEquals(
            listOf(TunPlan.TunRoute("0.0.0.0", 0), TunPlan.TunRoute("::", 0)),
            plan.routes,
        )
    }

    @Test
    fun of_alwaysServesVirtualDnsAndPublicFallback() {
        // mapdns 虚拟 DNS 必须在，否则 DNS 泄漏；公网兜底 DNS 用于满足系统
        // NetworkMonitor 的探测，否则系统会判定该网络"无互联网"
        assertEquals(listOf(TunConfig.DNS_ADDRESS, TunConfig.FALLBACK_DNS), TunPlan.of(false).dnsServers)
        assertEquals(listOf(TunConfig.DNS_ADDRESS, TunConfig.FALLBACK_DNS), TunPlan.of(true).dnsServers)
    }

    @Test
    fun of_mtuStaysUnderQuicAndHevLimits() {
        // hev 回程 UDP 缓冲写死 1500，QUIC 禁止分片要求 >= 1200
        val mtu = TunPlan.of(true).mtu
        assertTrue("MTU $mtu must be <= 1500 (hev buffer)", mtu <= 1500)
        assertTrue("MTU $mtu must be >= 1200 (QUIC minimum)", mtu >= 1200)
        assertFalse("mapdns pool must not overlap carrier CGNAT 100.64.0.0/10", TunConfig.MAPDNS_NETWORK.startsWith("100.64."))
    }
}
