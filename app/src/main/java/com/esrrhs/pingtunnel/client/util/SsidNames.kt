package com.esrrhs.pingtunnel.client.util

/** SSID 文本规整（纯 JVM 逻辑，便于单测）。 */
object SsidNames {

    /** 去掉系统 SSID 外的双引号；未知 SSID 归一为 null。 */
    fun unquote(raw: String?): String? {
        if (raw == null) return null
        val s = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return s.takeIf { it.isNotBlank() && it != UNKNOWN_SSID && it != "0x" }
    }

    private const val UNKNOWN_SSID = "<unknown ssid>"
}
