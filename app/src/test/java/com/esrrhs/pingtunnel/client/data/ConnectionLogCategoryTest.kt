package com.esrrhs.pingtunnel.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.esrrhs.pingtunnel.client.vpn.TunConfig
import org.junit.Test

class ConnectionLogCategoryTest {

    @Test
    fun plainDnsAndDotAreDnsSessions() {
        assertTrue(entry(proto = "UDP", port = 53).isDnsQuery)
        assertTrue(entry(proto = "TCP", port = 53).isDnsQuery)
        assertTrue(entry(proto = "TCP", port = 853).isDnsQuery)
        // mDNS/LLMNR 之类的本地解析不算 DNS 查询会话
        assertFalse(entry(proto = "UDP", port = 5353).isDnsQuery)
    }

    @Test
    fun dohToResolversIsDnsButOrdinaryHttpsIsNot() {
        // 实测刷屏的就是这两类：系统/应用对公共解析器的加密 DNS 探测
        assertTrue(entry(ip = "8.8.8.8", proto = "UDP", port = 443).isDnsQuery)
        assertTrue(entry(ip = "1.1.1.1", proto = "TCP", port = 443).isDnsQuery)
        assertTrue(entry(ip = TunConfig.FALLBACK_DNS, proto = "UDP", port = 443).isDnsQuery)
        assertFalse(entry(ip = "8.8.8.8", proto = "TCP", port = 80).isDnsQuery)
        assertFalse(entry(ip = "104.16.150.136", proto = "TCP", port = 443).isDnsQuery)
        assertFalse(entry(ip = "1.1.1.1", proto = "TCP", port = 80).isDnsQuery)
    }

    @Test
    fun dnsStoreKeepsItsOwnSmallerWindow() {
        val now = 10_000_000L
        val entries = (0 until 900).map { entry(proto = "UDP", port = 53, endMs = now - it) }
        val pruned = pruneConnectionLogs(
            entries,
            now,
            DnsLogRepository.MAX_ENTRIES,
            DnsLogRepository.MAX_AGE_MS,
        )
        assertEquals(DnsLogRepository.MAX_ENTRIES, pruned.size)
    }

    @Test
    fun dnsStoreDropsEntriesOlderThanOneDay() {
        val now = 2 * 24L * 60 * 60 * 1000
        val fresh = entry(proto = "UDP", port = 53, endMs = now - 1_000)
        val stale = entry(proto = "UDP", port = 53, endMs = now - DnsLogRepository.MAX_AGE_MS - 1)
        val pruned = pruneConnectionLogs(
            listOf(stale, fresh),
            now,
            DnsLogRepository.MAX_ENTRIES,
            DnsLogRepository.MAX_AGE_MS,
        )
        assertEquals(listOf(fresh), pruned)
    }

    private fun entry(
        proto: String,
        port: Int,
        ip: String = "203.0.113.7",
        endMs: Long = 1_000L,
    ) = ConnectionLogEntry(
        key = "k$proto$port$ip$endMs",
        uid = 1,
        label = "app",
        proto = proto,
        remoteIp = ip,
        remotePort = port,
        startMs = endMs - 10,
        endMs = endMs,
    )
}
