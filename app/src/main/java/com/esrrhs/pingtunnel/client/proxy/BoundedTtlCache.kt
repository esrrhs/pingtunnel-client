package com.esrrhs.pingtunnel.client.proxy

import java.util.Collections
import java.util.LinkedHashMap

/**
 * 有 TTL 且容量有硬上限的小型线程安全缓存。
 *
 * 用于分流器的实例级缓存（直连惩罚、DNS 解析结果）：这些缓存在 VPN 会话内
 * 长期存活、键基数不受控（UDP 惩罚键含端口、域名随浏览增长），若只在键被
 * 再次查询时惰性清理过期项、又没有容量上限，长会话（以天计）会无界增长。
 *
 * - 容量超限时按 LinkedHashMap 顺序淘汰最旧条目（[accessOrder]=true 时为 LRU）；
 * - [get] 对已过期条目视为未命中（返回 null），但**不删除**：解析失败时
 *   [getStale] 仍需取到它沿用上次成功结果；过期条目占的空间由容量淘汰兜底；
 * - [getStale] 无视 TTL 返回（DNS 解析失败时沿用上次成功结果）；
 * - 所有操作用同一把锁，读写都是哈希表量级的极短临界区。
 */
internal class BoundedTtlCache<V>(
    private val maxEntries: Int,
    accessOrder: Boolean = false,
) {
    private data class Entry<V>(val value: V, val expireAt: Long)

    private val map: MutableMap<String, Entry<V>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry<V>>(16, 0.75f, accessOrder) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Entry<V>>,
            ): Boolean = size > maxEntries
        },
    )

    /** 命中且未过期返回值；条目不存在或已过期返回 null（过期条目不删除，留给 getStale/容量淘汰）。 */
    fun get(key: String, now: Long): V? = synchronized(map) {
        val entry = map[key] ?: return null
        if (now >= entry.expireAt) null else entry.value
    }

    /** 无视 TTL 返回条目（含已过期）：供解析失败时沿用上次成功结果。 */
    fun getStale(key: String): V? = synchronized(map) { map[key]?.value }

    fun put(key: String, value: V, expireAt: Long) {
        map[key] = Entry(value, expireAt)
    }

    fun clear() {
        map.clear()
    }

    val size: Int get() = synchronized(map) { map.size }
}
