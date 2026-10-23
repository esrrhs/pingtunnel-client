package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 基于真实 APNIC chnroute 数据的精度回归测试（数据文件缺失时跳过，CI 因仓库包含该文件而执行）。
 *
 * 用户态精确分流时代：TUN 全量抓包，[IpRouteMatcher] 用**零聚合**的完整 CN 集合
 * 判定直连。本测试锁死两件事：
 *  1. CN 样本全部判为直连；
 *  2. 曾被旧 /12 块聚合误伤的非 CN 地址（1.1.1.1、Facebook 段等）必须判为代理，
 *     防止以后再引入任何形式的近似扩张。
 */
class CnRouteTest {

    private val asset = File("src/main/assets/cn_ipv4_cidr.txt")

    private fun requireCnList(): List<String> {
        assumeTrue("chnroute asset missing", asset.exists())
        return asset.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun cnSamplesGoDirect() {
        val matcher = IpRouteMatcher(requireCnList())
        listOf(
            "1.0.1.1", "114.114.114.114", "180.76.76.76",
            "210.2.4.8", "220.181.38.148",
        ).forEach { ip ->
            assertTrue("$ip should go direct", matcher.contains(ip))
        }
    }

    @Test
    fun foreignSamplesAreProxied_noBlockPadding() {
        val matcher = IpRouteMatcher(requireCnList())
        listOf(
            "8.8.8.8", "8.8.4.4", "9.9.9.9", "1.1.1.1",
            "208.67.222.222", "20.44.145.247",
            // 旧 /12 聚合把整个 157.240.0.0/16（Facebook）扩成了直连
            "157.240.1.1", "157.240.255.255",
        ).forEach { ip ->
            assertFalse("$ip must be proxied (regression of CN block padding)", matcher.contains(ip))
        }
    }

    @Test
    fun exactCnAllocationBoundary() {
        val matcher = IpRouteMatcher(requireCnList())
        // APNIC 数据中 1.1.0.0/24 是 CN 分配：块内直连，紧邻的 1.1.1.1 必须代理
        assertTrue(matcher.contains("1.1.0.0"))
        assertTrue(matcher.contains("1.1.0.255"))
        assertFalse(matcher.contains("1.1.1.1"))
        assertFalse(matcher.contains("1.0.255.255"))
    }

    @Test
    fun privateRangesAreNotPartOfCnSet() {
        val matcher = IpRouteMatcher(requireCnList())
        // 私有段是否直连由 bypass LAN 开关单独决定，CN 集合本身不应包含它们
        listOf("10.0.0.1", "192.168.1.1", "127.0.0.1", "100.64.0.1").forEach { ip ->
            assertFalse("$ip is not a CN public allocation", matcher.contains(ip))
        }
    }

    @Test
    fun cnDatasetIsLoadedAtExpectedScale() {
        val cidrs = requireCnList()
        val matcher = IpRouteMatcher(cidrs)
        println("CN v4 exact ranges: ${cidrs.size} allocations, ${matcher.rangeCount} merged intervals")
        assertTrue("chnroute dataset shrunk unexpectedly: ${cidrs.size}", cidrs.size > 8000)
        // 精确集合合并全部相邻/重叠区间后仍有 4k+ 段（旧 /12 块扩张只有几百个块）。
        // 注：区间允许任意边界（不要求对齐成 CIDR），所以比 ipaddress.collapse 更少。
        assertTrue("matcher must keep exact ranges, was ${matcher.rangeCount}", matcher.rangeCount > 4000)
    }
}
