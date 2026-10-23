package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 基于真实 APNIC CN IPv6 数据的精度回归测试（缺文件则跳过）。
 * 用户态精确分流：完整 CN v6 CIDR 进入 [IpRouteMatcher]，不做任何块扩展。
 */
class CnRoute6DataTest {

    private val asset = File("src/main/assets/cn_ipv6_cidr.txt")

    private fun cnV6(): List<String> {
        assumeTrue("cn v6 asset missing", asset.exists())
        return asset.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun cnV6_cnPrefixesGoDirect() {
        val matcher = IpRouteMatcher(cnV6())
        // 从 CN 分配段中取样：网段起始地址与若干偏移地址都应直连
        cnV6().take(50).map { it.substringBefore("/") }.forEach { ip ->
            assertTrue("$ip (CN) should go direct", matcher.contains(ip))
        }
    }

    @Test
    fun cnV6_nonCnGlobalAddressesAreProxied() {
        val matcher = IpRouteMatcher(cnV6())
        listOf(
            "2001:4860:4860::8888", // Google DNS
            "2606:4700:4700::1111", // Cloudflare
            "2620:fe::fe",          // Quad9
        ).forEach { ip ->
            assertFalse("$ip must be proxied", matcher.contains(ip))
        }
    }

    @Test
    fun cnV6_datasetIsLoadedAtExpectedScale() {
        val cidrs = cnV6()
        val matcher = IpRouteMatcher(cidrs)
        println("CN v6 exact ranges: ${cidrs.size} allocations, ${matcher.rangeCount} merged intervals")
        assertTrue("cn v6 dataset shrunk: ${cidrs.size}", cidrs.size > 1500)
        assertTrue("matcher must keep exact ranges, was ${matcher.rangeCount}", matcher.rangeCount > 1800)
    }
}
