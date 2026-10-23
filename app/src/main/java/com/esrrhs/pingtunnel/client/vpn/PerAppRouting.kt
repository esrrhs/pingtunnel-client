package com.esrrhs.pingtunnel.client.vpn

import com.esrrhs.pingtunnel.client.pingtunnel.PerAppMode

/**
 * 分应用代理的最终路由名单（纯逻辑，便于单测）。
 *
 * @property allowedPackages 需要 addAllowedApplication 的包（仅 [PerAppMode.ALLOWED]）
 * @property disallowedPackages 需要 addDisallowedApplication 的包
 */
internal data class PerAppRouting(
    val allowedPackages: Set<String>,
    val disallowedPackages: Set<String>,
)

/**
 * 把用户配置（可能来自外部导入）解析成 VpnService.Builder 可用的白/黑名单。
 *
 * 硬性不变量：**VPN 自身包名必须始终在黑名单**。本进程内的 spp 子进程与
 * RuleSocksServer 的直连 socket 与本 App 同 UID，一旦被路由进 TUN 就会形成
 * 「VPN 套 VPN」环路。UI 选择器虽然排除了自身，但 perAppPackages 是持久化字段、
 * 也能经导入配置携带，因此这里是权威兜底；同时把自身从白名单剔除，避免同一个包
 * 同时出现在允许/禁止名单导致 Builder 抛异常。
 */
internal object PerAppRoutingResolver {

    fun resolve(
        mode: PerAppMode,
        packages: Collection<String>,
        selfPackage: String,
    ): PerAppRouting {
        // 去重；已卸载的包仍原样交给 Builder（由 runCatching 吞 NameNotFound）
        val others = packages.toSet() - selfPackage
        return when (mode) {
            PerAppMode.ALL -> PerAppRouting(emptySet(), setOf(selfPackage))
            PerAppMode.ALLOWED -> PerAppRouting(others, setOf(selfPackage))
            PerAppMode.DISALLOWED -> PerAppRouting(emptySet(), others + selfPackage)
        }
    }
}
