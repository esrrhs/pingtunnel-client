package com.esrrhs.pingtunnel.client.util

/**
 * 可信 WiFi 判定（纯逻辑，便于单测）。
 *
 * 系统返回的 SSID 可能带双引号（"Home"）或为 <unknown ssid>（无定位权限），
 * 统一在 [WifiNames] 里规整后再与用户配置做精确匹配。
 */
object TrustedWifi {

    fun isTrusted(ssid: String?, trusted: Set<String>): Boolean =
        ssid != null && ssid.isNotBlank() && trusted.any { it == ssid }
}
