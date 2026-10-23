package com.esrrhs.pingtunnel.client.pingtunnel

import kotlinx.serialization.Serializable
import java.util.UUID

/** 分应用代理模式。 */
@Serializable
enum class PerAppMode {
    /** 代理全部 App（自身仍排除以防环路）。 */
    ALL,

    /** 仅代理选中的 App。 */
    ALLOWED,

    /** 代理除选中之外的 App。 */
    DISALLOWED,
}

/**
 * 一个独立的服务器配置项（类似 Shadowsocks 的 profile）。
 *
 * 每个配置可单独点击启用；[txBytes]/[rxBytes] 为该配置跨会话累计的上下行字节数；
 * [pingMs] 为最近一次延迟测试结果（-1 未测）。
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val config: PingtunnelConfig = PingtunnelConfig(),
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val perAppPackages: List<String> = emptyList(),
    /** 绕过局域网/私有网段（智能分流）。 */
    val bypassLan: Boolean = false,
    /** CN 地址直连、其余走代理（chnroute，智能分流）。 */
    val bypassCn: Boolean = false,
    val pingMs: Int = -1,
) {
    /** 返回首个校验错误码（名称与连接参数）；null 表示合法。 */
    fun validate(): ValidationError? = when {
        name.isBlank() -> ValidationError.NAME_REQUIRED
        perAppMode == PerAppMode.ALLOWED && perAppPackages.isEmpty() ->
            ValidationError.APPS_REQUIRED
        else -> config.validate()
    }
}
