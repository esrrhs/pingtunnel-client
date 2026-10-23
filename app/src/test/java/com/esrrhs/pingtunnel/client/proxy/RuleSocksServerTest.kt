package com.esrrhs.pingtunnel.client.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class RuleSocksServerTest {

    private fun daemonPool(): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true }
        }

    @Test
    fun runWithBudget_fastWork_returnsValue() {
        val result = RuleSocksServer.runWithBudget(daemonPool(), 1000) { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun runWithBudget_slowWork_timesOutAndReturnsNullFast() {
        val start = System.currentTimeMillis()
        val result = RuleSocksServer.runWithBudget(daemonPool(), 100) {
            Thread.sleep(800)
            "late"
        }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(null, result)
        assertTrue("must return near the budget, took ${elapsed}ms", elapsed < 600)
    }

    @Test
    fun runWithBudget_throwingWork_returnsNull() {
        val result = RuleSocksServer.runWithBudget(daemonPool(), 1000) {
            throw java.net.UnknownHostException("x")
        }
        assertEquals(null, result)
    }

    @Test
    fun enableMobileKeepAlive_setsFlagAndKeepsSocketUsable() {
        // JVM 上 android.system.Os 反射失败被吞，SO_KEEPALIVE 仍应生效且连接不受影响。
        java.net.ServerSocket(0).use { server ->
            val client = java.net.Socket()
            client.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 2000)
            val peer = server.accept()
            try {
                client.enableMobileKeepAlive()
                assertTrue("SO_KEEPALIVE must be enabled", client.keepAlive)
                client.getOutputStream().write(42)
                assertEquals(42, peer.getInputStream().read())
            } finally {
                client.close()
                peer.close()
            }
        }
    }

    /**
     * 并发连接围栏：线程池无界、每条会话占两个 fd，必须有硬上限。
     * 达到上限时新连接要在**握手之前**就断掉——放它们进握手等于占用线程和 10s 读预算，
     * 反而更容易把已建立会话一起拖死。
     */
    @Test
    fun clientLimit_exceeded_newConnectionIsRejectedBeforeHandshake() {
        val server = RuleSocksServer(SocksUpstream("127.0.0.1", 1080), emptySet(), maxClients = 1)
        server.start()
        try {
            val port = server.port!!
            // 第一条连接占住唯一名额：不发握手，握手读预算内不会被回收
            val first = java.net.Socket()
            first.connect(InetSocketAddress("127.0.0.1", port), 2000)
            try {
                val second = java.net.Socket()
                second.soTimeout = 3000
                second.connect(InetSocketAddress("127.0.0.1", port), 2000)
                assertEquals(
                    "connection beyond the cap must be closed before SOCKS negotiation",
                    -1,
                    second.getInputStream().read(),
                )
                second.close()
            } finally {
                first.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun upstreamRelayAddress_loopbackUses127001() {
        val server = RuleSocksServer(SocksUpstream("127.0.0.1", 1080), emptySet())
        val addr = server.upstreamRelayAddress("0.0.0.0", 50000)
        assertEquals(InetSocketAddress("127.0.0.1", 50000), addr)
    }

    @Test
    fun upstreamRelayAddress_remoteWithPrivateIp_fallsBackToUpstreamHost() {
        val server = RuleSocksServer(SocksUpstream("sh.esrrhs.xyz", 1080), emptySet())
        // 10.0.0.2 is a private IP returned by server behind NAT
        val addr = server.upstreamRelayAddress("10.0.0.2", 51880)
        assertEquals(InetSocketAddress("sh.esrrhs.xyz", 51880), addr)
    }

    @Test
    fun upstreamRelayAddress_remoteWithZeroOrBlank_fallsBackToUpstreamHost() {
        val server = RuleSocksServer(SocksUpstream("sh.esrrhs.xyz", 1080), emptySet())
        val addrZero = server.upstreamRelayAddress("0.0.0.0", 51880)
        assertEquals(InetSocketAddress("sh.esrrhs.xyz", 51880), addrZero)

        val addrBlank = server.upstreamRelayAddress("", 51880)
        assertEquals(InetSocketAddress("sh.esrrhs.xyz", 51880), addrBlank)
    }

    @Test
    fun upstreamRelayAddress_remoteWithPublicIp_usesBoundHost() {
        val server = RuleSocksServer(SocksUpstream("sh.esrrhs.xyz", 1080), emptySet())
        val addr = server.upstreamRelayAddress("1.1.1.1", 51880)
        assertEquals(InetSocketAddress("1.1.1.1", 51880), addr)
    }

    // ---------------- 用户态分流分类 ----------------

    private val ipv4 = Socks5Codec.ATYP_IPV4.toInt()
    private val ipv6 = Socks5Codec.ATYP_IPV6.toInt()
    private val domain = Socks5Codec.ATYP_DOMAIN.toInt()

    @Test
    fun classify_cnCidrs_onlyExactAllocationsGoDirect() {
        // 模拟 chnroute 中的 1.1.0.0/24（CN）与 220.181.0.0/16
        val server = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", 1080),
            directDomains = emptySet(),
            directIpv4Cidrs = listOf("1.1.0.0/24", "220.181.0.0/16"),
        )
        assertTrue(server.shouldDirect("1.1.0.1", ipv4))
        assertTrue(server.shouldDirect("1.1.0.255", ipv4))
        assertTrue(server.shouldDirect("220.181.38.148", ipv4))
        // 旧 /21、/12 聚合回归点：紧邻 CN 分配的非 CN 地址必须代理
        assertFalse(server.shouldDirect("1.1.1.1", ipv4))
        assertFalse(server.shouldDirect("8.8.8.8", ipv4))
        assertFalse(server.shouldDirect("157.240.1.1", ipv4))
        // 未配置 v6 规则时，所有 v6 代理
        assertFalse(server.shouldDirect("2400:3200::1", ipv6))
        // 域名规则为空时，域名一律代理
        assertFalse(server.shouldDirect("baidu.com", domain))
    }

    @Test
    fun classify_bypassPrivate_coversLoopbackRfc1918AndCgnat() {
        val server = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", 1080),
            directDomains = emptySet(),
            bypassPrivate = true,
        )
        // v4 私有/CGNAT/回环/链路本地
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.5.5", "172.31.255.255",
            "192.168.9.9", "100.64.0.1", "100.127.255.255", "169.254.1.1",
        ).forEach { assertTrue("$it should be LAN-direct", server.shouldDirect(it, ipv4)) }
        // 公网仍然代理（CGNAT 边界外）
        listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "169.253.0.1").forEach {
            assertFalse("$it should stay proxied", server.shouldDirect(it, ipv4))
        }
        // v6 回环/ULA/link-local
        listOf("::1", "fc00::1", "fdab::1", "fe80::12").forEach {
            assertTrue("$it should be LAN-direct", server.shouldDirect(it, ipv6))
        }
        assertFalse(server.shouldDirect("2001:4860:4860::8888", ipv6))
    }

    @Test
    fun udpDirectPenalty_cacheLifecycle() {
        val server = RuleSocksServer(SocksUpstream("127.0.0.1", 1080), emptySet())
        val key = server.udpDirectKey("8.8.8.8", 443)
        assertFalse(server.isDirectPenalizedKey(key))
        server.penalizeDirectKey(key)
        assertTrue(server.isDirectPenalizedKey(key))
        // udp: 前缀键不得与 TCP 的裸 host 键互相串味
        assertFalse(server.isDirectPenalizedKey("8.8.8.8"))
    }

    @Test
    fun classify_domainRules_matchDomainAndSubdomains() {
        val server = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", 1080),
            directDomains = setOf("baidu.com", "taobao.com"),
        )
        assertTrue(server.shouldDirect("baidu.com", domain))
        assertTrue(server.shouldDirect("www.baidu.com", domain))
        assertFalse(server.shouldDirect("notbaidu.com", domain))
        assertFalse(server.shouldDirect("example.com", domain))
        // 域名规则不影响 IP 字面量
        assertFalse(server.shouldDirect("1.2.3.4", ipv4))
    }
}
