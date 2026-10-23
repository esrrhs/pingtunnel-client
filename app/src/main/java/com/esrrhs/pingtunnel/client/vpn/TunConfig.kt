package com.esrrhs.pingtunnel.client.vpn

/**
 * TUN 接口常量，与 hev-socks5-tunnel 示例配置对齐。
 *
 * DNS 使用 hev 的 mapdns（fake-ip）虚拟地址：DNS 查询由 hev 在本地应答，
 * 返回 100.64.0.0/10 段的映射 IP，后续连接经 SOCKS5 携带域名由服务端解析，
 * 因此系统 DNS 永远不会直连运营商。
 */
object TunConfig {
    const val SESSION = "SPP"

    /**
     * 必须同时满足两条上限：
     * 1. hev 回程 UDP 缓冲写死 1500 字节（含 SOCKS5 头），更大的报文会被截断；
     * 2. QUIC 禁止 IP 分片，服务端会把 UDP 载荷原样发出，公网路径大约只有 1500。
     * 1400 时 IPv4 载荷约 1372，加上域名型 SOCKS 头仍低于 1500，且不低于 QUIC 要求的 1200。
     * TCP 浏览不受影响：隧道里是字节流，由服务端按真实路径 MSS 重新分段。
     */
    const val MTU = 1400

    const val TUN_ADDRESS = "198.18.0.1"
    const val TUN_PREFIX = 30

    const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1"
    const val TUN_PREFIX_V6 = 126

    /** mapdns 虚拟 DNS 地址（hev 配置中的 mapdns.address）。 */
    const val DNS_ADDRESS = "198.18.0.2"
    /** 公网 DNS，用于满足系统 NetworkMonitor 对 DoT/DNS 的探测并避免私有 DNS 超时。 */
    const val FALLBACK_DNS = "8.8.8.8"
    /**
     * fake-ip 池位于 TUN 本地基准段 198.18.0.0/15 内（RFC 2544 基准测试段，无真实
     * 互联网地址）：TUN 全量抓包后这些地址必然进入 hev 并被 mapdns 反查回域名。
     *
     * 不能使用 100.64.0.0/10——那是运营商 CGNAT 段，bypass LAN 时 100.64 的真实
     * 专网流量（营业厅/IPTV 等）需在用户态直连；fake-ip 池与之重叠会互相串流。
     * /18 提供 16K 地址，大于 mapdns cache-size（10000）。
     */
    const val MAPDNS_NETWORK = "198.18.64.0"
    const val MAPDNS_NETMASK = "255.255.192.0"
}
