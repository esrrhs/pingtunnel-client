package com.esrrhs.pingtunnel.client.proxy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP 上游建链失败的隔离性：一个会话的失败不得让其它会话的报文作废。
 *
 * 线上症状：开 VPN 时 YouTube 能列表，点播放却起不来、播放按钮立刻弹回（偶现）。
 * 根因是上游 ASSOCIATE 失败会设一个**实例级**冷却，冷却窗内写线程对**所有**会话
 * "取出即弃"——于是后台任一 UDP 会话失败一次，随后数秒内新建的 QUIC 流（起播那
 * 一下）握手包被全部静默丢掉。
 *
 * 本测试把这条性质钉住：A 会话持续建链失败后，共享同一份全局建链状态的 B 会话
 * 仍然要发起自己的建链尝试，且不能被 A 的退避拖到几秒之后。
 */
class UdpUpstreamBackoffTest {

    private val running = AtomicBoolean(true)
    private val relays = mutableListOf<DatagramSocket>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "udp-backoff-test").apply { isDaemon = true }
    }
    /** 生产里这两个对象是实例级共享的——正是它们把 A 的失败扩散给了 B。 */
    private val nextConnectAtMs = AtomicLong(0)
    private val connectGate = Any()

    /** 每会话独立的建链尝试计数；openUpstream 恒失败，模拟上游不可用。 */
    private class Attempts(val count: AtomicInteger = AtomicInteger(0))

    private fun deps(attempts: Attempts) = UdpDeps(
        upstream = SocksUpstream("127.0.0.1", 1),
        running = running,
        relays = relays,
        pool = pool,
        nextConnectAtMs = nextConnectAtMs,
        connectGate = connectGate,
        shouldDirect = { _, _ -> false }, // 全部走代理，不碰直连/DNS
        isDirectPenalizedKey = { false },
        penalizeDirectKey = { },
        udpDirectKey = { host, port -> "udp:$host:$port" },
        upstreamRelayAddress = { _, port -> InetSocketAddress("127.0.0.1", port) },
        resolveHost = { emptyList() },
        openUpstream = {
            attempts.count.incrementAndGet()
            throw IOException("upstream down (test)")
        },
        isLoopback = { true },
    )

    private fun newSession(attempts: Attempts): Pair<UdpSplitter, DatagramSocket> {
        // control 只参与 isClosed 判定，用一条真实回环连接避免"未连接 socket"的边界行为。
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val control = Socket()
        try {
            control.connect(listener.localSocketAddress)
        } finally {
            listener.close()
        }
        val clientRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        return UdpSplitter(clientRelay, control, deps(attempts)) to clientRelay
    }

    private fun sendProxiedDatagram(relayPort: Int) {
        val header = Socks5Codec.buildUdpHeader(
            Socks5Codec.ATYP_IPV4.toInt(),
            "1.1.1.1",
            9,
        )
        val payload = "PROBE".toByteArray(Charsets.UTF_8)
        val pkt = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, pkt, 0, header.size)
        System.arraycopy(payload, 0, pkt, header.size, payload.size)
        DatagramSocket().use { sender ->
            sender.send(
                DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
            )
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timeout waiting for $what")
    }

    @Test
    fun oneSessionUpstreamFailure_doesNotVoidAnotherSessionsDatagram() {
        val attemptsA = Attempts()
        val attemptsB = Attempts()
        val (splitterA, relayA) = newSession(attemptsA)
        val (splitterB, relayB) = newSession(attemptsB)
        val threads = listOf(splitterA, splitterB).map {
            Thread({ it.run() }, "udp-splitter-test").apply { isDaemon = true; start() }
        }
        try {
            // A 先失败一次（可能接着重试第二次），进入每会话退避。
            sendProxiedDatagram(relayA.localPort)
            waitUntil("A 发起第一次建链", 5_000) { attemptsA.count.get() >= 1 }

            // 关键：B 此刻才新建会话并发送第一包。旧实现里 B 落在 A 设的实例级冷却窗中，
            // 报文被"取出即弃"，B 一次建链都不会发起；新实现里退避是每会话的，B 照常尝试。
            val bStart = System.currentTimeMillis()
            sendProxiedDatagram(relayB.localPort)
            waitUntil("B 发起自己的建链", 5_000) { attemptsB.count.get() >= 1 }
            val bElapsed = System.currentTimeMillis() - bStart

            assertTrue(
                "B 的首包不得被 A 的失败拖住（旧实现会等到 5s 冷却结束），实测 ${bElapsed}ms",
                bElapsed < UdpTuning.UPSTREAM_BACKOFF_MAX_MS / 2,
            )
        } finally {
            threads.forEach { it.interrupt() }
            listOf(splitterA, splitterB).forEach { it.close() }
            listOf(relayA, relayB).forEach { runCatching { it.close() } }
            running.set(false)
            pool.shutdownNow()
        }
    }

    @Test
    fun upstreamFailure_delaysRatherThanDrops_subsequentPacketRetries() {
        // 上游恒失败时，写线程应"退避后重试"而不是把报文一次性作废：
        // 同一会话连发两包，尝试次数应继续增长（说明队列仍在被消费并重试）。
        val attempts = Attempts()
        val (splitter, relay) = newSession(attempts)
        val thread = Thread({ splitter.run() }, "udp-splitter-test").apply {
            isDaemon = true
            start()
        }
        try {
            sendProxiedDatagram(relay.localPort)
            waitUntil("第一次建链", 5_000) { attempts.count.get() >= 1 }
            sendProxiedDatagram(relay.localPort)
            waitUntil("失败后仍在退避重试", 5_000) { attempts.count.get() >= 2 }
        } finally {
            thread.interrupt()
            splitter.close()
            runCatching { relay.close() }
            running.set(false)
            pool.shutdownNow()
        }
    }
}
