package com.esrrhs.pingtunnel.client.e2e

import com.esrrhs.pingtunnel.client.proxy.Socks5Codec
import java.io.DataInputStream
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
 * 完整实现的 Mock SOCKS5 代理服务端，用于 E2E 测试验证。
 *
 * 特性：
 * 1. 支持 NO-AUTH 以及 USER/PASS 认证协商 (RFC 1928 / RFC 1929)
 * 2. 支持 TCP CONNECT (IPv4, IPv6, Domain)
 * 3. 支持 UDP ASSOCIATE (RFC 1928)，支持中继双向 UDP 报文 (IPv4 / IPv6)
 * 4. 统计连接计数与失败计数，支持高并发稳定转发
 */
class MockUpstreamSocks5Server(
    val bindHost: String = "127.0.0.1",
    private val expectedUser: String = "",
    private val expectedPass: String = "",
) : AutoCloseable {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mock-socks5").apply { isDaemon = true }
    }
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeDatagrams = ConcurrentHashMap.newKeySet<DatagramSocket>()

    // 分流测试断言用：真正到达上游的 CONNECT / UDP ASSOCIATE 次数
    private val connectCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val udpAssociateCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val udpRelayedCount = java.util.concurrent.atomic.AtomicInteger(0)
    val tcpConnectCount: Int get() = connectCount.get()
    val udpAssociateRequests: Int get() = udpAssociateCount.get()
    /** 经代理转发到本上游的已封装 UDP 报文数（含未能解析目标、被丢弃的）。 */
    val udpRelayedDatagrams: Int get() = udpRelayedCount.get()

    /**
     * 模拟「运行中上游掉线」：掐断当前所有活跃中继连接（入站客户端连接 + 到目标的连接），
     * 但服务本身继续 accept——用于验证被代理端能感知断链、且后续新会话可自愈重建。
     */
    fun dropActiveTcpConnections() {
        val victims = activeSockets.toList()
        activeSockets.clear()
        victims.forEach { runCatching { it.close() } }
    }

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start() {
        if (running.getAndSet(true)) return
        val ss = ServerSocket(0, 512, InetAddress.getByName(bindHost))
        serverSocket = ss
        pool.execute { acceptLoop(ss) }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            activeSockets.add(client)
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val `in` = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            // 1. Version & Auth Method
            val ver = `in`.readUnsignedByte()
            if (ver != 5) return
            val nMethods = `in`.readUnsignedByte()
            val methods = ByteArray(nMethods)
            `in`.readFully(methods)

            val requiresAuth = expectedUser.isNotEmpty()
            val chosenMethod = if (requiresAuth) {
                if (methods.contains(0x02.toByte())) 0x02.toByte() else 0xFF.toByte()
            } else {
                if (methods.contains(0x00.toByte())) 0x00.toByte() else 0xFF.toByte()
            }

            out.write(byteArrayOf(0x05, chosenMethod))
            out.flush()
            if (chosenMethod == 0xFF.toByte()) return

            // 2. User/Password auth if required
            if (chosenMethod == 0x02.toByte()) {
                val subVer = `in`.readUnsignedByte()
                if (subVer != 1) return
                val uLen = `in`.readUnsignedByte()
                val uBytes = ByteArray(uLen)
                `in`.readFully(uBytes)
                val user = String(uBytes, Charsets.UTF_8)

                val pLen = `in`.readUnsignedByte()
                val pBytes = ByteArray(pLen)
                `in`.readFully(pBytes)
                val pass = String(pBytes, Charsets.UTF_8)

                if (user == expectedUser && pass == expectedPass) {
                    out.write(byteArrayOf(0x01, 0x00)) // Auth success
                    out.flush()
                } else {
                    out.write(byteArrayOf(0x01, 0x01)) // Auth failure
                    out.flush()
                    return
                }
            }

            // 3. Request
            val req = Socks5Codec.readRequest(`in`)
            when (req.command) {
                Socks5Codec.CMD_CONNECT.toInt() -> handleConnect(req, `in`, out, client)
                Socks5Codec.CMD_UDP_ASSOCIATE.toInt() -> handleUdpAssociate(out, client)
                else -> {
                    out.write(Socks5Codec.failureReply(Socks5Codec.REP_COMMAND_NOT_SUPPORTED))
                    out.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
            activeSockets.remove(client)
        }
    }

    private fun handleConnect(
        req: Socks5Codec.Request,
        clientIn: DataInputStream,
        clientOut: OutputStream,
        client: Socket,
    ) {
        connectCount.incrementAndGet()
        val target = try {
            val addr = InetAddress.getByName(req.host)
            val s = try {
                java.nio.channels.SocketChannel.open().socket()
            } catch (_: Exception) {
                Socket()
            }
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, req.port), 3000)
            s
        } catch (e: Exception) {
            runCatching {
                clientOut.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                clientOut.flush()
            }
            return
        }
        activeSockets.add(target)

        try {
            clientOut.write(Socks5Codec.successReply("127.0.0.1", target.localPort))
            clientOut.flush()
            relayTcp(client, target)
        } catch (_: Exception) {
        } finally {
            runCatching { target.close() }
            activeSockets.remove(target)
        }
    }

    private fun relayTcp(a: Socket, b: Socket) {
        val latch = java.util.concurrent.CountDownLatch(2)
        val copy = { from: Socket, to: Socket ->
            try {
                val buf = ByteArray(16384)
                val input = from.getInputStream()
                val out = to.getOutputStream()
                while (running.get() && !from.isClosed && !to.isClosed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    if (!to.isClosed && !to.isOutputShutdown) to.shutdownOutput()
                }
                latch.countDown()
            }
        }

        pool.execute { copy(a, b) }
        pool.execute { copy(b, a) }

        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleUdpAssociate(clientOut: OutputStream, clientControl: Socket) {
        udpAssociateCount.incrementAndGet()
        // 创建 UDP relay socket (IPv4)
        val relaySocket = try {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            clientOut.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
            clientOut.flush()
            return
        }
        activeDatagrams.add(relaySocket)

        // 创建 UDP relay socket (IPv6)
        val ipv6RelaySocket = try {
            DatagramSocket(0, InetAddress.getByName("::1"))
        } catch (_: Exception) {
            null
        }
        ipv6RelaySocket?.let { activeDatagrams.add(it) }

        try {
            clientOut.write(Socks5Codec.successReply("127.0.0.1", relaySocket.localPort))
            clientOut.flush()

            val clientPeer = java.util.concurrent.atomic.AtomicReference<InetSocketAddress>()

            // 监听 IPv6 回包
            if (ipv6RelaySocket != null) {
                pool.execute {
                    val v6Buf = ByteArray(65535)
                    while (running.get() && !clientControl.isClosed && !ipv6RelaySocket.isClosed) {
                        val p = DatagramPacket(v6Buf, v6Buf.size)
                        try {
                            ipv6RelaySocket.soTimeout = 1000
                            ipv6RelaySocket.receive(p)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        } catch (_: Exception) {
                            break
                        }
                        val clientDest = clientPeer.get() ?: continue
                        val sender = p.socketAddress as? InetSocketAddress ?: continue
                        val targetHost = sender.address.hostAddress ?: "::1"
                        val targetPort = sender.port
                        val header = Socks5UdpHeader.encode(targetHost, targetPort, true)
                        val rawUdp = ByteArray(header.size + p.length)
                        System.arraycopy(header, 0, rawUdp, 0, header.size)
                        System.arraycopy(p.data, p.offset, rawUdp, header.size, p.length)
                        relaySocket.send(DatagramPacket(rawUdp, rawUdp.size, clientDest))
                    }
                }
            }

            // 监听客户端发来的 UDP SOCKS 封装包，拆包并转发给目标真实服务器；并把目标返回的包封包发回客户端
            val buf = ByteArray(65535)
            while (running.get() && !clientControl.isClosed && !relaySocket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    relaySocket.soTimeout = 1000
                    relaySocket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: java.net.PortUnreachableException) {
                    continue // 中继到关端口目标引发的 ICMP，非致命，保持中继循环存活
                } catch (_: Exception) {
                    break
                }

                val senderAddr = packet.socketAddress as InetSocketAddress
                val data = packet.data
                val len = packet.length

                // 判断是否是来自客户端的 SOCKS5 封装包 (前两字节 RSV = 0x00 0x00, FRAG = 0x00)
                if (len >= 10 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte()) {
                    clientPeer.set(senderAddr)
                    // 解析目标地址与端口
                    val (targetAddr, payloadOffset) = parseSocksUdpHeader(data, len) ?: continue
                    udpRelayedCount.incrementAndGet() // 已收到一条代理转发报文
                    val payloadLen = len - payloadOffset
                    if (payloadLen > 0 && !targetAddr.isUnresolved) {
                        // 无法解析的域名目标（如 .invalid 回退用例）直接丢弃，
                        // 不让异常终结整个 ASSOCIATE 转发循环。
                        val isTargetV6 = targetAddr.address is java.net.Inet6Address
                        if (isTargetV6 && ipv6RelaySocket != null) {
                            val outgoingPacket = DatagramPacket(data, payloadOffset, payloadLen, targetAddr)
                            ipv6RelaySocket.send(outgoingPacket)
                        } else {
                            val outgoingPacket = DatagramPacket(data, payloadOffset, payloadLen, targetAddr)
                            relaySocket.send(outgoingPacket)
                        }
                    }
                } else {
                    // 来自目标服务器的原生回包，封装 SOCKS5 头并送回 clientPeer
                    val clientDest = clientPeer.get() ?: continue
                    val targetHost = senderAddr.address.hostAddress ?: "127.0.0.1"
                    val targetPort = senderAddr.port
                    val isIpv6 = senderAddr.address is java.net.Inet6Address
                    val header = Socks5UdpHeader.encode(targetHost, targetPort, isIpv6)
                    val rawUdp = ByteArray(header.size + len)
                    System.arraycopy(header, 0, rawUdp, 0, header.size)
                    System.arraycopy(data, 0, rawUdp, header.size, len)

                    val respPacket = DatagramPacket(rawUdp, rawUdp.size, clientDest)
                    relaySocket.send(respPacket)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { relaySocket.close() }
            runCatching { ipv6RelaySocket?.close() }
            activeDatagrams.remove(relaySocket)
            ipv6RelaySocket?.let { activeDatagrams.remove(it) }
        }
    }

    private fun parseSocksUdpHeader(buf: ByteArray, len: Int): Pair<InetSocketAddress, Int>? {
        if (len < 4) return null
        val atyp = buf[3].toInt() and 0xFF
        var offset = 4
        val (host, nextOffset) = when (atyp) {
            0x01 -> { // IPv4
                if (len < offset + 4 + 2) return null
                val ip = "${buf[offset].toInt() and 0xFF}.${buf[offset + 1].toInt() and 0xFF}.${buf[offset + 2].toInt() and 0xFF}.${buf[offset + 3].toInt() and 0xFF}"
                ip to offset + 4
            }
            0x03 -> { // Domain
                if (len < offset + 1) return null
                val dLen = buf[offset].toInt() and 0xFF
                offset += 1
                if (len < offset + dLen + 2) return null
                val d = String(buf, offset, dLen, Charsets.US_ASCII)
                d to offset + dLen
            }
            0x04 -> { // IPv6
                if (len < offset + 16 + 2) return null
                val bytes = buf.copyOfRange(offset, offset + 16)
                val ip6 = InetAddress.getByAddress(bytes).hostAddress ?: "::1"
                ip6 to offset + 16
            }
            else -> return null
        }
        val portHi = buf[nextOffset].toInt() and 0xFF
        val portLo = buf[nextOffset + 1].toInt() and 0xFF
        val port = (portHi shl 8) or portLo
        val payloadOffset = nextOffset + 2
        return InetSocketAddress(host, port) to payloadOffset
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        activeDatagrams.forEach { runCatching { it.close() } }
        activeDatagrams.clear()
        pool.shutdownNow()
    }
}

object Socks5UdpHeader {
    fun encode(host: String, port: Int, isIpv6: Boolean): ByteArray {
        val atyp = if (isIpv6) 0x04.toByte() else 0x01.toByte()
        val addrBytes = if (isIpv6) {
            Socks5Codec.ipv6Bytes(host)
        } else {
            host.split(".").map { it.toInt().toByte() }.toByteArray()
        }
        val portBytes = byteArrayOf((port ushr 8).toByte(), port.toByte())
        return byteArrayOf(0, 0, 0, atyp) + addrBytes + portBytes
    }
}
