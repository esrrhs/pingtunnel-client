package com.esrrhs.pingtunnel.client.data

/** 全局设置（独立于具体配置）。 */
data class AppSettings(
    /** 开机/重启后自动连接上次配置。 */
    val bootStart: Boolean = true,
    /** 意外断线时自动重连（退避重试，无限次）。 */
    val autoReconnect: Boolean = true,
    /** 重连失败后自动切换到延迟最低的其它配置。 */
    val failover: Boolean = true,
    /** 新建配置默认绕过局域网。 */
    val defaultBypassLan: Boolean = true,
    /** 接入可信 WiFi 时暂停 VPN，离开后自动恢复。 */
    val trustedWifiEnabled: Boolean = false,
    /** 可信 WiFi SSID 集合（精确匹配，去掉系统返回的引号）。 */
    val trustedWifiSsids: Set<String> = emptySet(),
    /** 启用域名直连规则（位于 hev 与 socks5_client 之间的本地分流，内置大陆域名表）。 */
    val domainDirectEnabled: Boolean = true,
    /** 域名直连规则原文（每行一个域名，保存时不解析；启动时规整）。 */
    val domainDirectRulesText: String = "",
)
