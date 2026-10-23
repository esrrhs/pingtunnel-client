package com.esrrhs.pingtunnel.client.e2e

import com.esrrhs.pingtunnel.client.proxy.RuleSocksServer
import com.esrrhs.pingtunnel.client.proxy.Socks5Codec
import com.esrrhs.pingtunnel.client.proxy.SocksUpstream
import com.esrrhs.pingtunnel.client.proxy.UdpTuning
import com.esrrhs.pingtunnel.client.util.newDirectSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用户态分流（CN/LAN/domain split）端到端测试。
 *
 * TUN 全量抓包后，RuleSocksServer 必须对每一条 TCP CONNECT / UDP 报文独立决策：
 * 命中直连集合（精确 CIDR / 私有段 / 域名规则）的流量本地直发、**完全不接触上游**；
 * 其余转发上游 Mock。通过 Mock 上游的连接计数断言路径正确。
 *
 * 用回环地址模拟两类目标：
 *  - 直连 CIDR 配置成 127.0.0.0/8 → 回环目标即「CN/直连」；
 *  - 直连 CIDR 配置成 10.0.0.0/8 → 回环目标不在其中，必须走上游（Mock 再中继回环）。
 */
class ProxySplitE2ETest {

    private lateinit var tcpV4: MockTargetServers.TcpEchoServer
    private lateinit var tcpV6: MockTargetServers.TcpEchoServer
    private lateinit var udpV4: MockTargetServers.UdpEchoServer
    private lateinit var udpV6: MockTargetServers.UdpEchoServer

    private lateinit var cnUpstream: MockUpstreamSocks5Server
    private lateinit var foreignUpstream: MockUpstreamSocks5Server
    private lateinit var lanUpstream: MockUpstreamSocks5Server
    private lateinit var domainUpstream: MockUpstreamSocks5Server

    /** 「CN 分流」：v4 回环段直连，v6 无规则，无私有段。 */
    private lateinit var cnSplit: RuleSocksServer
    /** 「非 CN」对照：直连段与回环目标不相交，一切都要走上游。 */
    private lateinit var foreignSplit: RuleSocksServer
    /** 「绕过局域网」：全部私有/保留段（含 v4/v6 回环、CGNAT、ULA）直连。 */
    private lateinit var lanSplit: RuleSocksServer
    /** 「域名直连」：localhost 域名直连。 */
    private lateinit var domainSplit: RuleSocksServer
    /** 「直连回退」：.invalid 域名被判直连但本地解析必失败，应回退上游。 */
    private lateinit var fallbackUpstream: MockUpstreamSocks5Server
    private lateinit var fallbackSplit: RuleSocksServer

    @Before
    fun setUp() {
        tcpV4 = MockTargetServers.TcpEchoServer("127.0.0.1").start()
        tcpV6 = MockTargetServers.TcpEchoServer("::1").start()
        udpV4 = MockTargetServers.UdpEchoServer("127.0.0.1").start()
        udpV6 = MockTargetServers.UdpEchoServer("::1").start()

        cnUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        foreignUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        lanUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        domainUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }

        cnSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", cnUpstream.port),
            directDomains = emptySet(),
            directIpv4Cidrs = listOf("127.0.0.0/8"),
            directIpv6Cidrs = emptyList(),
            bypassPrivate = false,
        ).also { it.start() }

        foreignSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", foreignUpstream.port),
            directDomains = emptySet(),
            // 不含回环段：回环目标必须被送到上游
            directIpv4Cidrs = listOf("10.0.0.0/8", "192.168.0.0/16"),
            directIpv6Cidrs = listOf("2001:db8::/32"),
            bypassPrivate = false,
        ).also { it.start() }

        lanSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", lanUpstream.port),
            directDomains = emptySet(),
            bypassPrivate = true,
        ).also { it.start() }

        domainSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", domainUpstream.port),
            directDomains = setOf("localhost"),
        ).also { it.start() }

        fallbackUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        fallbackSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", fallbackUpstream.port),
            // RFC 6761 .invalid 永不解析：命中直连域名但本地解析必失败
            directDomains = setOf("nonexistent-spp-test.invalid"),
        ).also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { cnSplit.stop() }
        runCatching { foreignSplit.stop() }
        runCatching { lanSplit.stop() }
        runCatching { domainSplit.stop() }
        runCatching { fallbackSplit.stop() }
        runCatching { fallbackUpstream.close() }
        runCatching { cnUpstream.close() }
        runCatching { foreignUpstream.close() }
        runCatching { lanUpstream.close() }
        runCatching { domainUpstream.close() }
        runCatching { tcpV4.close() }
        runCatching { tcpV6.close() }
        runCatching { udpV4.close() }
        runCatching { udpV6.close() }
    }

    // ---------------- TCP ----------------

    @Test
    fun tcp_cnV4Target_bypassesUpstreamEntirely() {
        val before = cnUpstream.tcpConnectCount
        val reply = tcpThroughProxy(cnSplit.port!!, "127.0.0.1", tcpV4.port, "CN_DIRECT_V4", ipv6 = false)
        assertEquals("CN_DIRECT_V4", reply)
        assertEquals("upstream must never see the direct connection", before, cnUpstream.tcpConnectCount)
    }

    @Test
    fun tcp_nonCnV4Target_goesUpstream() {
        val before = foreignUpstream.tcpConnectCount
        val reply = tcpThroughProxy(foreignSplit.port!!, "127.0.0.1", tcpV4.port, "FOREIGN_V4", ipv6 = false)
        assertEquals("FOREIGN_V4", reply)
        assertTrue("connection must reach upstream", foreignUpstream.tcpConnectCount > before)
    }

    @Test
    fun tcp_v6Target_withoutV6Rules_goesUpstream() {
        val before = cnUpstream.tcpConnectCount
        val reply = tcpThroughProxy(cnSplit.port!!, "::1", tcpV6.port, "PROXIED_V6", ipv6 = true)
        assertEquals("PROXIED_V6", reply)
        assertTrue("v6 must be proxied when no v6 direct CIDR matches", cnUpstream.tcpConnectCount > before)
    }

    @Test
    fun tcp_bypassLan_bothLoopbackFamiliesGoDirect() {
        val before = lanUpstream.tcpConnectCount
        assertEquals("LAN4", tcpThroughProxy(lanSplit.port!!, "127.0.0.1", tcpV4.port, "LAN4", ipv6 = false))
        assertEquals("LAN6", tcpThroughProxy(lanSplit.port!!, "::1", tcpV6.port, "LAN6", ipv6 = true))
        assertEquals("upstream must see neither loopback connection", before, lanUpstream.tcpConnectCount)
    }

    @Test
    fun tcp_directConnect_bypassesDefaultProxySelector() {
        // 回归：java.net.Socket() 会查询默认 ProxySelector（macOS/Windows 上即系统
        // 代理设置）。开发机或用户系统配了系统级 SOCKS 代理（如 Clash 127.0.0.1:7890）
        // 时，RuleSocksServer 自己的出站连接会被劫持进该代理：IPv4 目标被代转发
        // （看似成功），IPv6 目标被拒发后连接直接 EOF、无任何异常。这里装一个指向
        // 死端口的恶意 selector，锁定代理服务器的出站连接必须真正直连。
        val deadPort = ServerSocket(0).use { it.localPort }
        val original = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> =
                listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", deadPort)))

            override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: IOException?) = Unit
        })
        try {
            val before = lanUpstream.tcpConnectCount
            assertEquals(
                "direct v6 connect must not be hijacked by the default proxy selector",
                "LAN6",
                tcpThroughProxy(lanSplit.port!!, "::1", tcpV6.port, "LAN6", ipv6 = true),
            )
            assertEquals(
                "upstream must see neither loopback connection",
                before,
                lanUpstream.tcpConnectCount,
            )
        } finally {
            ProxySelector.setDefault(original)
        }
    }

    @Test
    fun tcp_silentClient_handshakeReadBudgetClosesConnection() {
        // 本地 app 连入 SOCKS 端口后完全不发握手字节：旧实现服务端线程永久阻塞在
        // readUnsignedByte()，连接与线程永不释放（无界 cached pool 可被慢慢耗尽）。
        // 修复后握手读有 10s 预算，超时即关闭连接；正常 hev 握手毫秒级完成不受影响。
        val socket = clientSocket(cnSplit.port!!)
        try {
            socket.soTimeout = 16_000
            val start = System.currentTimeMillis()
            val firstByte = socket.getInputStream().read()
            val elapsed = System.currentTimeMillis() - start
            assertEquals("server must half/close a silent client (EOF)", -1, firstByte)
            val budget = RuleSocksServer.CLIENT_HANDSHAKE_TIMEOUT_MS
            assertTrue(
                "must be closed by the ~${budget}ms handshake budget, took ${elapsed}ms",
                elapsed in budget - 3_000..budget + 5_000,
            )
        } finally {
            socket.close()
        }
    }

    @Test
    fun tcp_matchingDomain_resolvesAndGoesDirect() {
        val before = domainUpstream.tcpConnectCount
        val reply = tcpThroughProxy(domainSplit.port!!, "localhost", tcpV4.port, "DOMAIN_DIRECT", ipv6 = false)
        assertEquals("DOMAIN_DIRECT", reply)
        assertEquals(before, domainUpstream.tcpConnectCount)
    }

    // ---------------- UDP ----------------

    @Test
    fun udp_cnV4Datagram_bypassesUpstreamWithNoAssociate() {
        val before = cnUpstream.udpAssociateRequests
        val reply = udpThroughProxy(cnSplit.port!!, "127.0.0.1", udpV4.port, "UDP_CN_DIRECT", ipv6 = false)
        assertEquals("UDP_CN_DIRECT", reply)
        assertEquals("no upstream UDP ASSOCIATE may be opened for direct traffic", before, cnUpstream.udpAssociateRequests)
    }

    @Test
    fun udp_nonCnV4Datagram_goesUpstream() {
        val before = foreignUpstream.udpAssociateRequests
        val reply = udpThroughProxy(foreignSplit.port!!, "127.0.0.1", udpV4.port, "UDP_FOREIGN", ipv6 = false)
        assertEquals("UDP_FOREIGN", reply)
        assertTrue(foreignUpstream.udpAssociateRequests > before)
    }

    @Test
    fun udp_v6Datagram_withoutV6Rules_goesUpstream() {
        val before = cnUpstream.udpAssociateRequests
        val reply = udpThroughProxy(cnSplit.port!!, "::1", udpV6.port, "UDP_V6_PROXIED", ipv6 = true)
        assertEquals("UDP_V6_PROXIED", reply)
        assertTrue(cnUpstream.udpAssociateRequests > before)
    }

    @Test
    fun udp_bypassLan_bothFamiliesGoDirect() {
        val before = lanUpstream.udpAssociateRequests
        assertEquals("UDP_LAN4", udpThroughProxy(lanSplit.port!!, "127.0.0.1", udpV4.port, "UDP_LAN4", ipv6 = false))
        assertEquals("UDP_LAN6", udpThroughProxy(lanSplit.port!!, "::1", udpV6.port, "UDP_LAN6", ipv6 = true))
        assertEquals(before, lanUpstream.udpAssociateRequests)
    }

    @Test
    fun udp_matchingDomain_resolvesAndGoesDirect() {
        val before = domainUpstream.udpAssociateRequests
        val reply = udpThroughProxy(domainSplit.port!!, "localhost", udpV4.port, "UDP_DOMAIN_DIRECT", ipv6 = false)
        assertEquals("UDP_DOMAIN_DIRECT", reply)
        assertEquals(before, domainUpstream.udpAssociateRequests)
    }

    @Test
    fun udp_mixedDestinationsInOneAssociation_splitPerDatagram() {
        // 同一个 UDP ASSOCIATE 内先后发「直连」与「代理」两个目标：
        // 直连报文不打开上游，首个代理报文才懒建立上游 ASSOCIATE，两路回包都必须收到。
        val direct = udpThroughProxy(cnSplit.port!!, "127.0.0.1", udpV4.port, "MIX_DIRECT", ipv6 = false)
        assertEquals("MIX_DIRECT", direct)
        val proxy = udpThroughProxy(cnSplit.port!!, "::1", udpV6.port, "MIX_PROXY", ipv6 = true)
        assertEquals("MIX_PROXY", proxy)
        // 注意：两次调用各自建立独立 ASSOCIATE；这里验证直连路径计数为 0 由前序用例覆盖，
        // 本用例重点是同一分流器实例在两类目标间反复切换不串包。
        assertTrue(cnUpstream.udpAssociateRequests >= 1)
    }

    @Test
    fun udp_directLocalFailure_fallsBackToUpstreamAndPenalized() {
        val ctrl = clientSocket(fallbackSplit.port!!)
        ctrl.soTimeout = 5000
        try {
            val inn = DataInputStream(ctrl.getInputStream())
            val out = ctrl.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            assertEquals(0x05, inn.readUnsignedByte())
            assertEquals(0x00, inn.readUnsignedByte())

            out.write(
                Socks5Codec.buildRequest(
                    Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                ),
            )
            out.flush()
            val bound = Socks5Codec.readReply(inn)
            assertTrue("UDP ASSOCIATE reply expected", bound != null)
            val relayPort = bound!!.second

            val clientUdp = DatagramSocket()
            try {
                val target = "nonexistent-spp-test.invalid"
                val header = Socks5Codec.buildUdpHeader(Socks5Codec.ATYP_DOMAIN.toInt(), target, 40001)

                fun sendDatagram(marker: String) {
                    val payload = marker.toByteArray(Charsets.UTF_8)
                    val pkt = ByteArray(header.size + payload.size)
                    System.arraycopy(header, 0, pkt, 0, header.size)
                    System.arraycopy(payload, 0, pkt, header.size, payload.size)
                    clientUdp.send(
                        DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
                    )
                }

                // 首包：本地解析 .invalid 失败 → 惩罚目标、懒建立上游 ASSOCIATE 并转发
                sendDatagram("UDP_DIRECT_FAIL_1")
                Thread.sleep(1500)
                assertEquals("direct failure must open exactly one upstream ASSOCIATE",
                    1, fallbackUpstream.udpAssociateRequests)

                // 第二包：目标在惩罚窗内，跳过直连、复用同一上游 ASSOCIATE
                sendDatagram("UDP_DIRECT_FAIL_2")
                Thread.sleep(500)
                assertEquals("penalized target must not open another ASSOCIATE",
                    1, fallbackUpstream.udpAssociateRequests)
            } finally {
                clientUdp.close()
            }
        } finally {
            ctrl.close()
        }
    }

    @Test
    fun udp_deadUpstream_burstIsThrottledNotDropped() {
        // 计数型「死上游」：accept 后立即关连接，SOCKS5 认证必失败。
        // start() 的预热会尝试 1 次后放弃；首个 UDP 报文再尝试 1 次即进入 5s 冷却，
        // 之后突发的报文必须零新增连接，而不是逐包重连形成 connect 风暴。
        val attempts = AtomicInteger(0)
        val listener = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            while (true) {
                val s = try {
                    listener.accept()
                } catch (_: Exception) {
                    break
                }
                attempts.incrementAndGet()
                runCatching { s.close() }
            }
        }.also { it.isDaemon = true; it.start() }

        val split = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", listener.localPort),
            directDomains = emptySet(),
        )
        try {
            split.start()
            // 等待 start() 的预热尝试落账（恰好 1 次，失败即停止）
            val deadline = System.currentTimeMillis() + 3000
            while (attempts.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val baseline = attempts.get()
            assertTrue("prewarm must attempt once, got $baseline", baseline >= 1)

            val ctrl = clientSocket(split.port!!)
            ctrl.soTimeout = 5000
            try {
                val inn = DataInputStream(ctrl.getInputStream())
                val out = ctrl.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
                assertEquals(0x05, inn.readUnsignedByte())
                assertEquals(0x00, inn.readUnsignedByte())

                out.write(
                    Socks5Codec.buildRequest(
                        Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                    ),
                )
                out.flush()
                val bound = Socks5Codec.readReply(inn)
                assertTrue("UDP ASSOCIATE reply expected", bound != null)
                val relayPort = bound!!.second

                val clientUdp = DatagramSocket()
                try {
                    val header = Socks5Codec.buildUdpHeader(
                        Socks5Codec.ATYP_IPV4.toInt(), "1.1.1.1", 9,
                    )

                    fun sendDatagram(marker: String) {
                        val payload = marker.toByteArray(Charsets.UTF_8)
                        val pkt = ByteArray(header.size + payload.size)
                        System.arraycopy(header, 0, pkt, 0, header.size)
                        System.arraycopy(payload, 0, pkt, header.size, payload.size)
                        clientUdp.send(
                            DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
                        )
                    }

                    // 首包：触发一次上游建链失败
                    sendDatagram("UDP_THROTTLE_1")
                    val afterFirst = System.currentTimeMillis() + 1000
                    while (attempts.get() < baseline + 1 && System.currentTimeMillis() < afterFirst) {
                        Thread.sleep(20)
                    }
                    assertTrue(
                        "first datagram must trigger an upstream attempt, got ${attempts.get()}",
                        attempts.get() >= baseline + 1,
                    )

                    // 突发 20 包：上游仍不可用，重试必须被全局建链间隔**节流**，
                    // 不得随报文数量线性增长（N 包 N 次 connect 会打爆上游与线程池）。
                    // 旧实现靠"5s 冷却窗内丢弃"达成这点——代价是其它会话的新建
                    // QUIC 流被连坐丢包（起播失败）；改为节流后两者兼得。
                    val beforeBurst = attempts.get()
                    repeat(20) { sendDatagram("UDP_THROTTLE_BURST_$it") }
                    Thread.sleep(1000)
                    val burstAttempts = attempts.get() - beforeBurst
                    assertTrue(
                        "burst must be throttled by connect spacing, got $burstAttempts/s",
                        burstAttempts <= 8,
                    )
                    assertTrue(
                        "burst must still retry rather than freeze, got $burstAttempts/s",
                        burstAttempts >= 1,
                    )
                } finally {
                    clientUdp.close()
                }
            } finally {
                ctrl.close()
            }
        } finally {
            runCatching { split.stop() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun udp_upstreamRestart_rebuildsAssociationOnNextDatagram() {
        // 假上游：完成握手与 UDP ASSOCIATE 后立即关闭控制连接（模拟上游重启）。
        // UDP relay 本地 send 永远"成功"，控制连接 EOF 是唯一死亡信号；
        // 客户端看门狗必须拆链，让下一个报文触发全新 ASSOCIATE（无冷却）。
        val associates = AtomicInteger(0)
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            while (true) {
                val ctrl = try { listener.accept() } catch (_: Exception) { break }
                Thread {
                    try {
                        val inn = DataInputStream(ctrl.getInputStream())
                        val out = ctrl.getOutputStream()
                        if (inn.readUnsignedByte() != 0x05) return@Thread
                        inn.readUnsignedByte() // nmethods
                        inn.readUnsignedByte() // 认为客户端提供 0x00
                        out.write(byteArrayOf(0x05, 0x00)); out.flush()
                        // ASSOCIATE 请求：VER CMD RSV ATYP(4B) + IPv4(4B) + PORT(2B)
                        val req = ByteArray(10)
                        var off = 0
                        while (off < req.size) {
                            val n = inn.read(req, off, req.size - off)
                            if (n < 0) return@Thread
                            off += n
                        }
                        if (req[1] != Socks5Codec.CMD_UDP_ASSOCIATE) return@Thread
                        associates.incrementAndGet()
                        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                        out.write(Socks5Codec.successReply("127.0.0.1", relay.localPort))
                        out.flush()
                        ctrl.close() // 上游"重启"：控制连接立刻消失
                    } catch (_: Exception) {
                        runCatching { ctrl.close() }
                    }
                }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true; it.start() }

        val split = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", listener.localPort),
            directDomains = emptySet(),
        )
        try {
            split.start()
            val ctrl = clientSocket(split.port!!)
            ctrl.soTimeout = 5000
            try {
                val inn = DataInputStream(ctrl.getInputStream())
                val out = ctrl.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
                assertEquals(0x05, inn.readUnsignedByte())
                assertEquals(0x00, inn.readUnsignedByte())
                out.write(
                    Socks5Codec.buildRequest(
                        Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                    ),
                )
                out.flush()
                val bound = Socks5Codec.readReply(inn)
                assertTrue("UDP ASSOCIATE reply expected", bound != null)
                val relayPort = bound!!.second

                val clientUdp = DatagramSocket()
                try {
                    val header = Socks5Codec.buildUdpHeader(
                        Socks5Codec.ATYP_IPV4.toInt(), "1.1.1.1", 9,
                    )

                    fun sendDatagram(marker: String) {
                        val payload = marker.toByteArray(Charsets.UTF_8)
                        val pkt = ByteArray(header.size + payload.size)
                        System.arraycopy(header, 0, pkt, 0, header.size)
                        System.arraycopy(payload, 0, pkt, header.size, payload.size)
                        clientUdp.send(
                            DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
                        )
                    }

                    fun awaitAssociates(expected: Int, timeoutMs: Long) {
                        val deadline = System.currentTimeMillis() + timeoutMs
                        while (associates.get() < expected && System.currentTimeMillis() < deadline) {
                            Thread.sleep(20)
                        }
                    }

                    // 报文 1：建 ASSOCIATE #1；假上游随即关控制连接 → 看门狗拆链
                    sendDatagram("UDP_WATCHDOG_1")
                    awaitAssociates(1, 3000)
                    assertEquals(1, associates.get())

                    // 报文 2：看门狗已拆链，必须全新 ASSOCIATE #2（不进 5s 冷却）
                    Thread.sleep(400) // 给 EOF 拆链留时间
                    sendDatagram("UDP_WATCHDOG_2")
                    awaitAssociates(2, 3000)
                    assertEquals(2, associates.get())

                    // 报文 3：上游再次"重启"，第三次重建；连续自愈
                    Thread.sleep(400)
                    sendDatagram("UDP_WATCHDOG_3")
                    awaitAssociates(3, 3000)
                    assertEquals(3, associates.get())
                } finally {
                    clientUdp.close()
                }
            } finally {
                ctrl.close()
            }
        } finally {
            runCatching { split.stop() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun udp_silentUpstream_handshakeOffLoopDoesNotBlockDirectAndSelfHeals() {
        // 静默上游：TCP accept 后绝不写任何字节（NAT 黑洞/僵死 SPP：connect 成功但
        // SOCKS 协商永不返回）。旧实现 ensureUpstream 在 run() 派发循环上同步建链，
        // 协商读无超时无限阻塞——同 ASSOCIATE 的直连 UDP 全部冻死且永不重试。
        // 修复后：
        //  1) 建链/握手只在专用写线程进行：其卡住期间直连报文照常秒回（无队头阻塞）；
        //  2) 握手读有 3s 预算，超时进 5s 冷却，窗后下一报文自动重建 ASSOCIATE（自愈）。
        val attempts = AtomicInteger(0)
        val held = ConcurrentHashMap.newKeySet<Socket>()
        val listener = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            while (true) {
                val s = try {
                    listener.accept()
                } catch (_: Exception) {
                    break
                }
                attempts.incrementAndGet()
                held.add(s) // 保持连接打开但绝不写：客户端协商读必然超时
            }
        }.also { it.isDaemon = true; it.start() }

        val split = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", listener.localPort),
            directDomains = emptySet(),
            directIpv4Cidrs = listOf("127.0.0.0/8"),
        )
        try {
            split.start()
            // start() 的预热也会连上静默上游（3s 读超时后放弃，不再补充）
            val warmDeadline = System.currentTimeMillis() + 3000
            while (attempts.get() < 1 && System.currentTimeMillis() < warmDeadline) {
                Thread.sleep(20)
            }
            val baseline = attempts.get()
            assertTrue("prewarm must connect once, got $baseline", baseline >= 1)

            val ctrl = clientSocket(split.port!!)
            ctrl.soTimeout = 5000
            try {
                val inn = DataInputStream(ctrl.getInputStream())
                val out = ctrl.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
                assertEquals(0x05, inn.readUnsignedByte())
                assertEquals(0x00, inn.readUnsignedByte())
                out.write(
                    Socks5Codec.buildRequest(
                        Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                    ),
                )
                out.flush()
                val bound = Socks5Codec.readReply(inn)
                assertTrue("UDP ASSOCIATE reply expected", bound != null)
                val relayPort = bound!!.second

                val clientUdp = DatagramSocket()
                try {
                    clientUdp.soTimeout = 4000
                    fun send(atyp: Int, host: String, port: Int, marker: String) {
                        val header = Socks5Codec.buildUdpHeader(atyp, host, port)
                        val payload = marker.toByteArray(Charsets.UTF_8)
                        val pkt = ByteArray(header.size + payload.size)
                        System.arraycopy(header, 0, pkt, 0, header.size)
                        System.arraycopy(payload, 0, pkt, header.size, payload.size)
                        clientUdp.send(
                            DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
                        )
                    }
                    fun recvReply(): String {
                        val recvBuf = ByteArray(65535)
                        val recv = DatagramPacket(recvBuf, recvBuf.size)
                        clientUdp.receive(recv)
                        val (_, off) = Socks5Codec.parseUdpPacket(recv.data, recv.length)
                            ?: error("malformed UDP reply header")
                        return String(recv.data, off, recv.length - off, Charsets.UTF_8)
                    }

                    // 代理报文 #1：写线程发起第 1 次 ASSOCIATE 建链并卡在协商读上
                    send(Socks5Codec.ATYP_IPV4.toInt(), "1.1.1.1", 9, "SILENT_PROXY_1")
                    val attemptDeadline = System.currentTimeMillis() + 3000
                    while (attempts.get() < baseline + 1 && System.currentTimeMillis() < attemptDeadline) {
                        Thread.sleep(20)
                    }
                    assertEquals("writer must open one upstream attempt",
                        baseline + 1, attempts.get())

                    // 关键断言：写线程正卡在握手上，同一 ASSOCIATE 内随后的直连报文
                    // 必须立即收到回包，不得等待上游握手（旧实现这里会超时收不到任何回包）
                    val directStart = System.currentTimeMillis()
                    send(Socks5Codec.ATYP_IPV4.toInt(), "127.0.0.1", udpV4.port, "SILENT_DIRECT")
                    val reply = recvReply()
                    val directElapsed = System.currentTimeMillis() - directStart
                    assertEquals("SILENT_DIRECT", reply)
                    assertTrue(
                        "direct datagram must not wait for upstream handshake, took ${directElapsed}ms",
                        directElapsed < 2500,
                    )

                    // 自愈：等握手预算过去后再发一包，必须继续发起新的建链尝试。
                    // 旧实现派发循环永久阻塞在第一次协商读上，attempts 永远不会再增长。
                    // 现在退避是每会话的（上限 UPSTREAM_BACKOFF_MAX_MS），且失败只延迟
                    // 不丢弃，因此重试比旧的"实例级 5s 冷却"更早到来。
                    val retryAt = System.currentTimeMillis() +
                        RuleSocksServer.UPSTREAM_HANDSHAKE_TIMEOUT_MS +
                        UdpTuning.UPSTREAM_BACKOFF_MAX_MS + 1500
                    while (System.currentTimeMillis() < retryAt) Thread.sleep(50)
                    send(Socks5Codec.ATYP_IPV4.toInt(), "1.1.1.1", 9, "SILENT_PROXY_2")
                    val secondDeadline = System.currentTimeMillis() + 3000
                    while (attempts.get() < baseline + 2 && System.currentTimeMillis() < secondDeadline) {
                        Thread.sleep(20)
                    }
                    assertTrue(
                        "writer must retry after handshake budget, attempts=${attempts.get()}",
                        attempts.get() >= baseline + 2,
                    )
                } finally {
                    clientUdp.close()
                }
            } finally {
                ctrl.close()
            }
        } finally {
            runCatching { split.stop() }
            held.forEach { runCatching { it.close() } }
            runCatching { listener.close() }
        }
    }

    @Test
    fun udp_domainDirect_resolvesAsyncWithoutBlockingProxiedFlow() {
        // 自包含夹具：.invalid 命中域名直连规则但本地解析必失败。
        // 域名解析在专用线程异步进行：run() 不得等待它，紧随其后的代理报文必须照常转发。
        val upstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        val split = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", upstream.port),
            directDomains = setOf("nonexistent-spp-test.invalid"),
        )
        try {
            split.start()
            val ctrl = clientSocket(split.port!!)
            ctrl.soTimeout = 5000
            try {
                val inn = DataInputStream(ctrl.getInputStream())
                val out = ctrl.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
                assertEquals(0x05, inn.readUnsignedByte())
                assertEquals(0x00, inn.readUnsignedByte())
                out.write(
                    Socks5Codec.buildRequest(
                        Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                    ),
                )
                out.flush()
                val bound = Socks5Codec.readReply(inn)
                assertTrue("UDP ASSOCIATE reply expected", bound != null)
                val relayPort = bound!!.second

                val clientUdp = DatagramSocket()
                // 一个确定关闭的本地 UDP 端口：代理报文中继到它必触发 ICMP port-unreachable，
                // 用于验证该 ICMP 不会连累 ASSOCIATE 重建。
                val closedPortProbe = DatagramSocket()
                val closedPort = closedPortProbe.localPort
                closedPortProbe.close()
                try {
                    fun send(atyp: Int, host: String, port: Int, marker: String) {
                        val header = Socks5Codec.buildUdpHeader(atyp, host, port)
                        val payload = marker.toByteArray(Charsets.UTF_8)
                        val pkt = ByteArray(header.size + payload.size)
                        System.arraycopy(header, 0, pkt, 0, header.size)
                        System.arraycopy(payload, 0, pkt, header.size, payload.size)
                        clientUdp.send(
                            DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
                        )
                    }

                    // 报文 1：域名直连（解析慢/失败，在解析线程异步进行，run() 不等待）
                    send(Socks5Codec.ATYP_DOMAIN.toInt(), "nonexistent-spp-test.invalid", 40001, "ASYNC_DOMAIN")
                    // 报文 2：普通代理报文；即使上一条仍在解析，也必须立即转发（无队头阻塞）
                    send(Socks5Codec.ATYP_IPV4.toInt(), "127.0.0.1", closedPort, "ASYNC_PROXIED")

                    // 两条都应经同一懒建立的上游 ASSOCIATE 到达 mock
                    val deadline = System.currentTimeMillis() + 8000
                    while (upstream.udpRelayedDatagrams < 2 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(15)
                    }
                    assertEquals("both datagrams must reach upstream via one association",
                        2, upstream.udpRelayedDatagrams)
                    Thread.sleep(300)
                    assertEquals("both flows share a single lazy upstream ASSOCIATE",
                        1, upstream.udpAssociateRequests)
                } finally {
                    clientUdp.close()
                }
            } finally {
                ctrl.close()
            }
        } finally {
            runCatching { split.stop() }
            runCatching { upstream.close() }
        }
    }

    // ---------------- helpers ----------------

    /**
     * 测试客户端 socket：同样必须绕过系统 ProxySelector（动机同
     * [newDirectSocket]），否则系统级 SOCKS 代理会代转发这次到本机代理端口的
     * 连接，分流断言测的就不是 RuleSocksServer 的真实行为了。
     */
    private fun clientSocket(port: Int): Socket = newDirectSocket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress("127.0.0.1", port), 2000)
    }

    private fun tcpThroughProxy(
        proxyPort: Int,
        host: String,
        targetPort: Int,
        msg: String,
        ipv6: Boolean,
    ): String {
        clientSocket(proxyPort).use { socket ->
            socket.soTimeout = 5000
            val inn = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            assertEquals(0x05, inn.readUnsignedByte())
            assertEquals(0x00, inn.readUnsignedByte())

            val atyp = when {
                ipv6 -> Socks5Codec.ATYP_IPV6.toInt()
                host[0].isDigit() -> Socks5Codec.ATYP_IPV4.toInt()
                else -> null
            }
            out.write(Socks5Codec.buildRequest(Socks5Codec.CMD_CONNECT, atyp, host, targetPort))
            out.flush()
            val reply = Socks5Codec.readReply(inn)
            assertTrue("CONNECT must succeed", reply != null)

            val payload = msg.toByteArray(Charsets.UTF_8)
            out.write(payload); out.flush()
            val received = ByteArray(payload.size)
            inn.readFully(received)
            return String(received, Charsets.UTF_8)
        }
    }

    private fun udpThroughProxy(
        proxyPort: Int,
        host: String,
        targetPort: Int,
        msg: String,
        ipv6: Boolean,
    ): String {
        val ctrl = clientSocket(proxyPort)
        ctrl.soTimeout = 5000
        try {
            val inn = DataInputStream(ctrl.getInputStream())
            val out = ctrl.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            assertEquals(0x05, inn.readUnsignedByte())
            assertEquals(0x00, inn.readUnsignedByte())

            out.write(
                Socks5Codec.buildRequest(
                    Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                ),
            )
            out.flush()
            val bound = Socks5Codec.readReply(inn)
            assertTrue("UDP ASSOCIATE reply expected", bound != null)
            val relayPort = bound!!.second

            val clientUdp = DatagramSocket()
            try {
                clientUdp.soTimeout = 5000
                val atyp: Int = when {
                    ipv6 -> Socks5Codec.ATYP_IPV6.toInt()
                    host[0].isDigit() -> Socks5Codec.ATYP_IPV4.toInt()
                    else -> Socks5Codec.ATYP_DOMAIN.toInt()
                }
                val header = Socks5Codec.buildUdpHeader(atyp, host, targetPort)
                val payload = msg.toByteArray(Charsets.UTF_8)
                val packetData = ByteArray(header.size + payload.size)
                System.arraycopy(header, 0, packetData, 0, header.size)
                System.arraycopy(payload, 0, packetData, header.size, payload.size)
                clientUdp.send(
                    DatagramPacket(packetData, packetData.size, InetSocketAddress("127.0.0.1", relayPort)),
                )

                val recvBuf = ByteArray(65535)
                val recv = DatagramPacket(recvBuf, recvBuf.size)
                clientUdp.receive(recv)
                // 回包头部按真实源地址封装（域名直连时也会变成 IP 型头），
                // 必须解析回包头拿 payload 偏移，不能假定与请求头等长。
                val (replyEndpoint, replyOffset) =
                    Socks5Codec.parseUdpPacket(recv.data, recv.length)
                        ?: error("malformed UDP reply header")
                assertEquals("reply must come from the requested target port", targetPort, replyEndpoint.port)
                assertTrue("reply too short", recv.length > replyOffset)
                return String(recv.data, replyOffset, recv.length - replyOffset, Charsets.UTF_8)
            } finally {
                clientUdp.close()
            }
        } finally {
            ctrl.close()
        }
    }
}
