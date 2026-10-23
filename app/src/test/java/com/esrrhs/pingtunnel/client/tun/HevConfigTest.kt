package com.esrrhs.pingtunnel.client.tun

import com.esrrhs.pingtunnel.client.vpn.TunConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hev YAML 生成的单测：hev 对配置格式很敏感（缺字段直接起不来），
 * 且这里的值必须和 `VpnService.Builder` 侧（[TunConfig]）完全一致。
 */
class HevConfigTest {

    @Test
    fun render_wiresTunnelToLocalSocksPort() {
        val yaml = HevConfig.render(45678, false, "/data/hev.log")

        assertTrue(yaml.contains("port: 45678"))
        assertTrue(yaml.contains("address: 127.0.0.1"))
        assertTrue(yaml.contains("udp: 'udp'")) // UDP 必须开，否则 DNS 与非 TCP 流量全断
        assertTrue(yaml.contains("log-file: /data/hev.log"))
    }

    @Test
    fun render_matchesTunConfig() {
        val yaml = HevConfig.render(1080, true, "/log")

        // 与 VpnService.Builder 同源，任一处改动而另一处没改都会导致隧道不通
        assertTrue(yaml.contains("mtu: ${TunConfig.MTU}"))
        assertTrue(yaml.contains("ipv4: ${TunConfig.TUN_ADDRESS}"))
        assertTrue(yaml.contains("ipv4: ${TunConfig.TUN_ADDRESS}"))
        assertTrue(yaml.contains("ipv6: '${TunConfig.TUN_ADDRESS_V6}'"))
        assertTrue(yaml.contains("address: ${TunConfig.DNS_ADDRESS}"))
        assertTrue(yaml.contains("network: ${TunConfig.MAPDNS_NETWORK}"))
        assertTrue(yaml.contains("netmask: ${TunConfig.MAPDNS_NETMASK}"))
    }

    @Test
    fun render_ipv6Disabled_omitsV6Line() {
        // hev 在没有 ipv6 地址时若仍声明 ipv6 段会起不来
        val yaml = HevConfig.render(1080, false, "/log")
        assertFalse("ipv6 line must be omitted", yaml.contains("ipv6:"))
    }

    @Test
    fun render_isStableYamlShape() {
        val yaml = HevConfig.render(1080, false, "/log")
        val sections = yaml.lineSequence().filter { it.isNotBlank() && !it.startsWith(" ") }
            .map { it.trimEnd(':') }
            .toList()
        assertEquals(listOf("tunnel", "socks5", "mapdns", "misc"), sections)
        // 缩进必须统一两空格，hev 的 YAML 解析不接受 tab/混合缩进
        assertTrue(
            yaml.lineSequence().filter { it.startsWith(" ") }.all { it.startsWith("  ") && !it.startsWith("\t") },
        )
    }
}
