package com.esrrhs.pingtunnel.client.util

import android.content.Context
import android.util.Log

/** 读取 assets 中的中国大陆 IPv4/IPv6 CIDR 列表。 */
object CnRouteList {

    private const val ASSET_V4 = "cn_ipv4_cidr.txt"
    private const val ASSET_V6 = "cn_ipv6_cidr.txt"

    @Volatile private var cachedV4: List<String>? = null
    @Volatile private var cachedV6: List<String>? = null

    fun loadV4(context: Context): List<String> {
        cachedV4?.let { return it }
        synchronized(this) {
            cachedV4?.let { return it }
            val list = read(context, ASSET_V4)
            cachedV4 = list
            return list
        }
    }

    fun loadV6(context: Context): List<String> {
        cachedV6?.let { return it }
        synchronized(this) {
            cachedV6?.let { return it }
            val list = read(context, ASSET_V6)
            cachedV6 = list
            return list
        }
    }

    /**
     * 资产读取失败（损坏的 APK / 极端 ROM 问题）时降级为空表而非抛异常：
     * 与 [BundledDirectDomains] 行为一致——调用方在 VPN 建链路径上，
     * 空表等价于"无 CN 直连规则"（全部走代理），连接仍可建立。
     */
    private fun read(context: Context, asset: String): List<String> =
        runCatching {
            context.assets.open(asset).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        }.onFailure {
            Log.e(TAG, "failed to load CN route asset $asset", it)
        }.getOrDefault(emptyList())

    private const val TAG = "CnRouteList"
}
