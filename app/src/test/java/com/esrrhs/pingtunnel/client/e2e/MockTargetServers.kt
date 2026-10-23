package com.esrrhs.pingtunnel.client.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 目标测试服务器集合：
 * 1. TcpEchoServer: 回显 TCP 收到的所有字节，支持 IPv4 ("127.0.0.1") 与 IPv6 ("::1")
 * 2. UdpEchoServer: 回显 UDP 数据报文，支持 IPv4 与 IPv6
 * 3. HttpEchoServer: 响应 HTTP 请求，回显请求 body 与 query
 */
object MockTargetServers {

    class TcpEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "tcp-echo-$host").apply { isDaemon = true }
        }
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()

        val port: Int get() = serverSocket?.localPort ?: 0

        fun start(): TcpEchoServer {
            if (running.getAndSet(true)) return this
            val addr = InetAddress.getByName(host)
            val ss = if (addr is java.net.Inet6Address) {
                val ch = java.nio.channels.ServerSocketChannel.open()
                ch.bind(InetSocketAddress(addr, 0), 512)
                ch.socket()
            } else {
                ServerSocket(0, 512, addr)
            }
            serverSocket = ss
            pool.execute {
                while (running.get()) {
                    val s = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    sockets.add(s)
                    pool.execute {
                        try {
                            s.tcpNoDelay = true
                            val buf = ByteArray(8192)
                            val input = s.getInputStream()
                            val out = s.getOutputStream()
                            while (running.get() && !s.isClosed) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                out.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { s.close() }
                            sockets.remove(s)
                        }
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { serverSocket?.close() }
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            pool.shutdownNow()
        }
    }

    class UdpEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "udp-echo-$host").apply { isDaemon = true }
        }

        val port: Int get() = socket?.localPort ?: 0

        fun start(): UdpEchoServer {
            if (running.getAndSet(true)) return this
            val s = DatagramSocket(0, InetAddress.getByName(host))
            socket = s
            pool.execute {
                val buf = ByteArray(65535)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        // 原路回显数据
                        val echo = DatagramPacket(p.data, p.offset, p.length, p.socketAddress)
                        s.send(echo)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { socket?.close() }
            pool.shutdownNow()
        }
    }

    class HttpEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private var server: HttpServer? = null

        val port: Int get() = server?.address?.port ?: 0

        fun start(): HttpEchoServer {
            val s = HttpServer.create(InetSocketAddress(InetAddress.getByName(host), 0), 64)
            s.createContext("/echo", HttpHandler { exchange ->
                val body = exchange.requestBody.readBytes()
                val resp = if (body.isNotEmpty()) body else "HELLO_HTTP_ECHO".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, resp.size.toLong())
                exchange.responseBody.use { it.write(resp) }
            })
            s.executor = Executors.newCachedThreadPool { r ->
                Thread(r, "http-echo-$host").apply { isDaemon = true }
            }
            s.start()
            server = s
            return this
        }

        override fun close() {
            server?.stop(0)
        }
    }

    /**
     * DNS Echo 服务端 (UDP)：
     * 解析请求 DNS Header 与 Query 域名，回传标准的 DNS Answer (A 记录 1.2.3.4)
     */
    class DnsEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dns-echo-$host").apply { isDaemon = true }
        }

        val port: Int get() = socket?.localPort ?: 0

        fun start(): DnsEchoServer {
            if (running.getAndSet(true)) return this
            val s = DatagramSocket(0, InetAddress.getByName(host))
            socket = s
            pool.execute {
                val buf = ByteArray(4096)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        if (p.length >= 12) {
                            // DNS Header: ID(2), Flags(2), QDCOUNT(2), ANCOUNT(2), NSCOUNT(2), ARCOUNT(2)
                            val txId = ((p.data[0].toInt() and 0xFF) shl 8) or (p.data[1].toInt() and 0xFF)
                            // 构造响应：Flags = 0x8180 (Standard query response, No error), QDCOUNT=1, ANCOUNT=1
                            val resp = java.io.ByteArrayOutputStream()
                            resp.write(byteArrayOf(
                                (txId shr 8).toByte(), (txId and 0xFF).toByte(),
                                0x81.toByte(), 0x80.toByte(), // Flags: response
                                0x00, 0x01,                   // QDCOUNT = 1
                                0x00, 0x01,                   // ANCOUNT = 1
                                0x00, 0x00,                   // NSCOUNT = 0
                                0x00, 0x00                    // ARCOUNT = 0
                            ))
                            // 拷贝 Query Section
                            resp.write(p.data, 12, p.length - 12)
                            // Answer Section: Name pointer 0xc00c, Type A (0x0001), Class IN (0x0001), TTL 60s, Len 4, IP 1.2.3.4
                            resp.write(byteArrayOf(
                                0xc0.toByte(), 0x0c.toByte(),
                                0x00, 0x01,
                                0x00, 0x01,
                                0x00, 0x00, 0x00, 0x3c,
                                0x00, 0x04,
                                0x01, 0x02, 0x03, 0x04
                            ))
                            val bytes = resp.toByteArray()
                            s.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { socket?.close() }
            pool.shutdownNow()
        }
    }

    /**
     * WebSocket 服务端 (TCP)：
     * 响应 HTTP 101 Switching Protocols，并在 WebSocket 数据帧上进行双向文本 Echo
     */
    class WebSocketEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "ws-echo-$host").apply { isDaemon = true }
        }

        val port: Int get() = serverSocket?.localPort ?: 0

        fun start(): WebSocketEchoServer {
            if (running.getAndSet(true)) return this
            val ss = ServerSocket(0, 16, InetAddress.getByName(host))
            serverSocket = ss
            pool.execute {
                while (running.get()) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    pool.execute {
                        try {
                            s.tcpNoDelay = true
                            val input = s.getInputStream()
                            val out = s.getOutputStream()
                            val buf = ByteArray(4096)
                            val n = input.read(buf)
                            if (n > 0) {
                                val req = String(buf, 0, n)
                                if (req.contains("Upgrade: websocket", ignoreCase = true)) {
                                    // 响应 101 Switching Protocols
                                    val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                                            "Upgrade: websocket\r\n" +
                                            "Connection: Upgrade\r\n" +
                                            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
                                    out.write(handshake.toByteArray(Charsets.UTF_8))
                                    out.flush()

                                    // 读取客户端发送的 masked frame (0x81 = text frame)
                                    val header = ByteArray(6)
                                    input.read(header)
                                    val len = header[1].toInt() and 0x7F
                                    val mask = ByteArray(4) { header[2 + it] }
                                    val payload = ByteArray(len)
                                    input.read(payload)
                                    for (i in 0 until len) {
                                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                                    }

                                    // 回送 unmasked text frame (0x81)
                                    val reply = ByteArray(2 + len)
                                    reply[0] = 0x81.toByte()
                                    reply[1] = len.toByte()
                                    System.arraycopy(payload, 0, reply, 2, len)
                                    out.write(reply)
                                    out.flush()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { s.close() }
                        }
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { serverSocket?.close() }
            pool.shutdownNow()
        }
    }

    /**
     * gRPC Mock 服务端 (基于 HTTP/2 + application/grpc):
     * 验证 Connection Preface、SETTINGS 握手以及 gRPC HEADERS / DATA 报文交互
     */
    class GrpcMockServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "grpc-mock-$host").apply { isDaemon = true }
        }

        val port: Int get() = serverSocket?.localPort ?: 0

        fun start(): GrpcMockServer {
            if (running.getAndSet(true)) return this
            val ss = ServerSocket(0, 16, InetAddress.getByName(host))
            serverSocket = ss
            pool.execute {
                while (running.get()) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    pool.execute {
                        try {
                            s.tcpNoDelay = true
                            val input = s.getInputStream()
                            val out = s.getOutputStream()
                            val preface = ByteArray(24)
                            input.read(preface)
                            if (String(preface).startsWith("PRI * HTTP/2.0")) {
                                // 消耗客户端 SETTINGS 帧
                                val frameHeader = ByteArray(9)
                                input.read(frameHeader)
                                val len = ((frameHeader[0].toInt() and 0xFF) shl 16) or
                                        ((frameHeader[1].toInt() and 0xFF) shl 8) or
                                        (frameHeader[2].toInt() and 0xFF)
                                if (len > 0) input.read(ByteArray(len))

                                // 发送服务端 SETTINGS 帧 (Type 0x04)
                                out.write(byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00))
                                out.flush()

                                // 读取客户端后续 gRPC 消息帧
                                val grpcFrame = ByteArray(9)
                                val readLen = input.read(grpcFrame)
                                if (readLen > 0) {
                                    // 回传 gRPC Status OK (HEADERS frame with END_STREAM)
                                    // 模拟 HTTP/2 HEADERS 帧 (Type 0x01, Flags 0x05 END_STREAM | END_HEADERS, Stream 1)
                                    out.write(byteArrayOf(0x00, 0x00, 0x04, 0x01, 0x05, 0x00, 0x00, 0x00, 0x01, 0x47, 0x52, 0x50, 0x43))
                                    out.flush()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { s.close() }
                        }
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { serverSocket?.close() }
            pool.shutdownNow()
        }
    }

    /**
     * QUIC Mock 服务端 (UDP)：
     * 接收客户端 QUIC Initial 报文 (带有 QUIC Long Header 0xC0 与 Version 0x00000001)，回显带协商的 QUIC 包
     */
    class QuicMockServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "quic-echo-$host").apply { isDaemon = true }
        }

        val port: Int get() = socket?.localPort ?: 0

        fun start(): QuicMockServer {
            if (running.getAndSet(true)) return this
            val s = DatagramSocket(0, InetAddress.getByName(host))
            socket = s
            pool.execute {
                val buf = ByteArray(65535)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        // 检查是否为 QUIC Long Header (0xC0 开头且包含 Version)
                        if (p.length >= 5 && (p.data[0].toInt() and 0xC0) == 0xC0) {
                            // 构造 QUIC 响应报文：Long Header, Version 1, 附带 QUIC_SERVER_ACK
                            val resp = java.io.ByteArrayOutputStream()
                            resp.write(byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01)) // Header & Version 1
                            val ackPayload = "QUIC_SERVER_HANDSHAKE_ACK".toByteArray(Charsets.UTF_8)
                            resp.write(ackPayload)
                            val bytes = resp.toByteArray()
                            s.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { socket?.close() }
            pool.shutdownNow()
        }
    }
}
