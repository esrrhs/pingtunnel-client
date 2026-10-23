package com.esrrhs.pingtunnel.client.util

import android.content.Context

/** assets 中内置的大陆直连域名表（构建脚本从 dnsmasq-china-list 生成），进程内缓存。 */
object BundledDirectDomains {

    @Volatile
    private var cached: Set<String>? = null

    fun load(context: Context): Set<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val rules = runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                    // 资产已是每行一个规整域名，直接读入集合；
                    // 过滤上游表混入的裸通用 TLD（如 top/wang，仅保留 cn 与中文 TLD）
                    reader.lineSequence()
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && DomainRuleMatcher.isBareTldAllowed(it) }
                        .toSet()
                }
            }.getOrDefault(emptySet())
            cached = rules
            return rules
        }
    }

    private const val ASSET_NAME = "domain_direct_cn.txt"
}
