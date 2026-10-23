package com.esrrhs.pingtunnel.client.vpn

import android.content.ContentResolver
import android.provider.Settings

/**
 * 系统「始终开启的 VPN」（Always-on）状态读取。
 *
 * Android 不允许 App 自行开启常驻 VPN，只能引导用户去系统设置打开；但**当前是否已开启**
 * 是可读的（`Settings.Secure` 里存着被选中的 VPN 包名），界面据此告诉用户现状，
 * 免得用户以为开了其实没开（或反过来）。
 */
object AlwaysOnVpn {

    /**
     * `Settings.Secure.ALWAYS_ON_VPN_APP` 的键值。该常量在 SDK 里被隐藏，
     * 只能按字面量读取；空/未设置即"未开启任何常驻 VPN"。
     */
    private const val KEY_ALWAYS_ON_VPN_APP = "always_on_vpn_app"

    /** 系统当前配置的常驻 VPN 包名；null = 读取失败或未设置。 */
    fun configuredApp(resolver: ContentResolver): String? = runCatching {
        Settings.Secure.getString(resolver, KEY_ALWAYS_ON_VPN_APP)
    }.getOrNull()

    /**
     * 常驻 VPN 是否指向本 App。
     *
     * 纯函数（不碰 Context），便于单测锁定：系统里配的是别的 VPN 时不能误报"已开启"。
     */
    fun isEnabledFor(configuredApp: String?, packageName: String): Boolean =
        !configuredApp.isNullOrBlank() && configuredApp == packageName
}
