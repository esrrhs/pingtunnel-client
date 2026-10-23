package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI

/**
 * 系统代理劫持防线的单测。
 *
 * 背景：开发机 / 用户机器配了系统级 SOCKS 代理（Clash 等）时，`java.net.Socket()`
 * 会把本 App 自己连本机 SOCKS 端口、以及分流直连的流量静默送进该代理——IPv4
 * 看似成功、IPv6 握手后直接 EOF。防线有两条：[newDirectSocket]（逐点）与
 * [DirectProxySelector]（进程级兜底），这里同时锁定两者。
 */
class DirectSocketsTest {

    @Test
    fun isLoopbackHost_coversAllForms() {
        listOf("127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "::1", "0:0:0:0:0:0:0:1", "[::1]")
            .forEach { assertTrue("$it must be loopback", isLoopbackHost(it)) }

        listOf(null, "", "10.0.0.1", "192.168.1.1", "128.0.0.1", "example.com", "fe80::1")
            .forEach { assertFalse("$it must NOT be loopback", isLoopbackHost(it)) }
    }

    @Test
    fun select_loopback_returnsNoProxyEvenWhenDelegateHijacks() {
        val selector = LoopbackNoProxySelector(hijackingSelector(deadPort()))

        val chosen = selector.select(URI.create("socket://127.0.0.1:1080"))
        assertEquals(listOf(Proxy.NO_PROXY), chosen)
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI.create("socket://[::1]:1080")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI.create("socket://localhost:1080")))
    }

    @Test
    fun select_nonLoopback_delegatesToPreviousDefault() {
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 7890))
        val selector = LoopbackNoProxySelector(fixedSelector(socks))

        assertEquals(listOf(socks), selector.select(URI.create("socket://1.1.1.1:443")))
    }

    @Test
    fun select_withoutDelegate_fallsBackToNoProxy() {
        // 没有原默认 selector（或它返回空）时必须回 NO_PROXY：调用方拿到空列表会直接失败
        assertEquals(listOf(Proxy.NO_PROXY), LoopbackNoProxySelector(null).select(URI.create("socket://1.1.1.1:443")))
        assertEquals(
            listOf(Proxy.NO_PROXY),
            LoopbackNoProxySelector(fixedSelector()).select(URI.create("socket://1.1.1.1:443")),
        )
    }

    @Test
    fun install_isIdempotent() {
        val original = ProxySelector.getDefault()
        try {
            DirectProxySelector.install()
            val first = ProxySelector.getDefault()
            assertTrue("install must put LoopbackNoProxySelector in place", first is LoopbackNoProxySelector)
            DirectProxySelector.install()
            assertSame("second install must not wrap the chain again", first, ProxySelector.getDefault())
        } finally {
            ProxySelector.setDefault(original)
        }
    }

    @Test
    fun socket_toLoopback_isNotHijackedOnceInstalled() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val original = ProxySelector.getDefault()
        try {
            // 装一个「连什么都要走死端口 SOCKS」的恶意默认 selector，再套上我们的兜底
            ProxySelector.setDefault(hijackingSelector(deadPort()))
            DirectProxySelector.install()

            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 2000)
                assertTrue("loopback connect must really go direct", socket.isConnected)
            }
        } finally {
            ProxySelector.setDefault(original)
            runCatching { server.close() }
        }
    }

    // ---- helpers ----

    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    private fun hijackingSelector(port: Int): ProxySelector = fixedSelector(
        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)),
    )

    private fun fixedSelector(vararg proxies: Proxy): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = proxies.toList()
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
}
