package com.esrrhs.pingtunnel.client.tun

import com.esrrhs.pingtunnel.client.vpn.TunConfig

/**
 * hev-socks5-tunnel 的 YAML 配置生成（纯函数，便于单测）。
 *
 * 与 [TunConfig] 同源：TUN 地址 / MTU / mapdns 段必须和 `VpnService.Builder` 侧
 * 完全一致，否则 hev 起不来或 DNS 走不通。
 */
object HevConfig {

    fun render(socksPort: Int, enableIpv6: Boolean, logFilePath: String): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: tun0")
        appendLine("  mtu: ${TunConfig.MTU}")
        appendLine("  ipv4: ${TunConfig.TUN_ADDRESS}")
        if (enableIpv6) appendLine("  ipv6: '${TunConfig.TUN_ADDRESS_V6}'")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: $socksPort")
        appendLine("  udp: 'udp'")
        appendLine("mapdns:")
        appendLine("  address: ${TunConfig.DNS_ADDRESS}")
        appendLine("  port: 53")
        appendLine("  network: ${TunConfig.MAPDNS_NETWORK}")
        appendLine("  netmask: ${TunConfig.MAPDNS_NETMASK}")
        appendLine("  cache-size: 10000")
        appendLine("misc:")
        appendLine("  log-level: info")
        appendLine("  log-file: $logFilePath")
    }
}
