package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpRouteMatcherTest {

    // ---- IPv4 ----

    @Test
    fun v4_emptyMatcher_neverMatches() {
        val m = IpRouteMatcher(emptyList())
        assertTrue(m.isEmpty)
        assertFalse(m.contains("8.8.8.8"))
    }

    @Test
    fun v4_defaultRoute_matchesEverything() {
        val m = IpRouteMatcher(listOf("0.0.0.0/0"))
        listOf("0.0.0.0", "1.1.1.1", "100.64.0.1", "127.0.0.1", "255.255.255.255")
            .forEach { assertTrue(it, m.contains(it)) }
    }

    @Test
    fun v4_slash24_boundariesAreExact() {
        val m = IpRouteMatcher(listOf("1.1.0.0/24"))
        assertTrue(m.contains("1.1.0.0"))
        assertTrue(m.contains("1.1.0.1"))
        assertTrue(m.contains("1.1.0.255"))
        assertFalse("1.1.1.1 must NOT be padded into a /24 (the old /21 bug)", m.contains("1.1.1.1"))
        assertFalse(m.contains("1.0.255.255"))
    }

    @Test
    fun v4_cgnatRange() {
        val m = IpRouteMatcher(listOf("100.64.0.0/10"))
        assertTrue(m.contains("100.64.0.0"))
        assertTrue(m.contains("100.127.255.255"))
        assertFalse(m.contains("100.63.255.255"))
        assertFalse(m.contains("101.0.0.0"))
        assertFalse(m.contains("8.8.8.8"))
    }

    @Test
    fun v4_overlappingAndAdjacentCidrsMerge() {
        val m = IpRouteMatcher(
            listOf("10.0.0.0/24", "10.0.0.128/25", "10.0.1.0/24", "8.8.8.8/32"),
        )
        assertTrue(m.contains("10.0.0.1"))
        assertTrue(m.contains("10.0.0.200"))
        assertTrue(m.contains("10.0.1.255"))
        assertFalse(m.contains("10.0.2.0"))
        assertTrue(m.contains("8.8.8.8"))
        assertFalse(m.contains("8.8.8.9"))
    }

    @Test
    fun v4_realWorldForeignSamples_neverMatchCnPadding() {
        // 复现旧 /12 聚合回归：这些非 CN 地址曾因块扩展被强制直连。
        // 用户态精确匹配只用真实 CIDR，这里用 CN 的 1.1.0.0/24 + 157.240 无关段验证。
        val m = IpRouteMatcher(
            listOf("1.1.0.0/24", "220.181.0.0/16", "114.114.114.0/24"),
        )
        listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "208.67.222.222", "157.240.1.1")
            .forEach { assertFalse("$it must be proxied", m.contains(it)) }
        listOf("1.1.0.55", "220.181.38.148", "114.114.114.114")
            .forEach { assertTrue("$it must be direct", m.contains(it)) }
    }

    @Test
    fun v4_hostRouteWithoutPrefix() {
        val m = IpRouteMatcher(listOf("8.8.8.8"))
        assertTrue(m.contains("8.8.8.8"))
        assertFalse(m.contains("8.8.8.7"))
        assertFalse(m.contains("8.8.8.9"))
    }

    @Test
    fun v4_invalidInput_returnsFalseInsteadOfThrowing() {
        val m = IpRouteMatcher(listOf("10.0.0.0/8"))
        assertFalse(m.contains("not.an.ip.address"))
        assertFalse(m.contains("999.1.1.1"))
        assertFalse(m.contains(""))
    }

    // ---- IPv6 ----

    @Test
    fun v6_defaultRoute_matchesEverything() {
        val m = IpRouteMatcher(listOf("::/0"))
        listOf("::", "::1", "2001:4860:4860::8888", "fe80::1", "ffff:ffff::1")
            .forEach { assertTrue(it, m.contains(it)) }
    }

    @Test
    fun v6_docPrefix_boundariesAreExact() {
        val m = IpRouteMatcher(listOf("2001:db8::/32"))
        // /32 只覆盖第三个组为 0000..ffff 的范围：2001:0db8:*（不包含 2001:db9）
        assertTrue(m.contains("2001:db8::1"))
        assertTrue(m.contains("2001:db8:abcd:1234::"))
        assertTrue(m.contains("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"))
        // 紧邻的 2001:db9:: 与 2001:db7:ffff:... 在段外
        assertFalse(m.contains("2001:db9::"))
        assertFalse(m.contains("2001:db9::1"))
        assertFalse(m.contains("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff"))
    }

    @Test
    fun v6_loopbackAndUla() {
        val m = IpRouteMatcher(listOf("::1/128", "fc00::/7", "fe80::/10"))
        assertTrue(m.contains("::1"))
        assertTrue(m.contains("fd12:3456::1"))
        assertTrue(m.contains("fe80::1234"))
        assertFalse(m.contains("2001:4860:4860::8888"))
        assertFalse(m.contains("::2"))
    }

    @Test
    fun v6_foreignDnsSamples_notInCnPrefixes() {
        val m = IpRouteMatcher(listOf("2400:3200::/32", "2400:da00::/32"))
        listOf("2001:4860:4860::8888", "2606:4700:4700::1111", "2620:fe::fe")
            .forEach { assertFalse("$it must be proxied", m.contains(it)) }
        assertTrue(m.contains("2400:3200:1::"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mixedFamilies_areRejected() {
        IpRouteMatcher(listOf("10.0.0.0/8", "2001:db8::/32"))
    }

    @Test
    fun largeRouteSet_lookupStaysCorrectAndFast() {
        // 模拟 chnroute 规模：1 万个互不相邻的 /24（每隔 4096 地址一个），不触发合并
        val cidrs = (0 until 10_000).map { i ->
            val base = i * 4096L
            "%d.%d.%d.0/24".format(base ushr 24, (base ushr 16) and 255, (base ushr 8) and 255)
        }
        val m = IpRouteMatcher(cidrs)
        assertTrue(m.contains("0.0.0.1"))
        assertTrue(m.contains("0.0.0.255"))
        assertFalse("gap between /24 blocks must not match", m.contains("0.0.1.1"))
        assertFalse(m.contains("208.67.222.222"))

        val start = System.nanoTime()
        repeat(100_000) { m.contains("208.67.222.222") }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("100k lookups should be fast, was ${elapsedMs}ms", elapsedMs < 1000)
        assertEquals(10_000, m.rangeCount)
    }
}
