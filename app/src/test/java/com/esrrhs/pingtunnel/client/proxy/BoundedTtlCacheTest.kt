package com.esrrhs.pingtunnel.client.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTtlCacheTest {

    @Test
    fun hitMissAndExpiry() {
        val cache = BoundedTtlCache<String>(4)
        cache.put("a", "v1", expireAt = 100)
        assertEquals("v1", cache.get("a", now = 50))
        assertNull(cache.get("missing", now = 50))

        // 到期即未命中
        assertNull(cache.get("a", now = 100))
        // 过期条目保留（getStale 兜底需要它），空间由容量淘汰回收
        assertEquals(1, cache.size)
    }

    @Test
    fun expiredEntryIsServedByGetStale() {
        // DNS 解析失败时需沿用上次成功结果（无视 TTL）
        val cache = BoundedTtlCache<String>(4)
        cache.put("a", "old", expireAt = 100)
        assertNull(cache.get("a", now = 200))
        assertEquals("old", cache.getStale("a"))
    }

    @Test
    fun capacityEvictsInInsertionOrder() {
        val cache = BoundedTtlCache<Int>(3)
        cache.put("a", 1, expireAt = 1_000)
        cache.put("b", 2, expireAt = 1_000)
        cache.put("c", 3, expireAt = 1_000)
        cache.put("d", 4, expireAt = 1_000) // 淘汰最旧的 a
        assertEquals(3, cache.size)
        assertNull(cache.get("a", now = 0))
        assertEquals(2, cache.get("b", now = 0))
        assertEquals(4, cache.get("d", now = 0))
    }

    @Test
    fun accessOrderKeepsRecentlyReadEntries() {
        val cache = BoundedTtlCache<Int>(3, accessOrder = true)
        cache.put("a", 1, expireAt = 1_000)
        cache.put("b", 2, expireAt = 1_000)
        cache.put("c", 3, expireAt = 1_000)
        // a 被读取后变成最近访问；再插入 d 时应淘汰 b（最久未访问）而非 a
        assertEquals(1, cache.get("a", now = 0))
        cache.put("d", 4, expireAt = 1_000)
        assertEquals(1, cache.get("a", now = 0))
        assertNull(cache.get("b", now = 0))
        assertEquals(4, cache.get("d", now = 0))
    }

    @Test
    fun putRefreshesTtlAndClearEmptiesCache() {
        val cache = BoundedTtlCache<String>(2)
        cache.put("a", "v1", expireAt = 100)
        assertNull(cache.get("a", now = 100))
        // 过期但未被 LRU 淘汰前，重新写入带新 TTL
        cache.put("a", "v2", expireAt = 500)
        assertEquals("v2", cache.get("a", now = 200))

        cache.clear()
        assertEquals(0, cache.size)
        assertTrue(cache.getStale("a") == null)
    }
}
