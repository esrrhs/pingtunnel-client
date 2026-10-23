package com.esrrhs.pingtunnel.client.proxy

import android.util.Log
import com.esrrhs.pingtunnel.client.util.DirectClassifier
import com.esrrhs.pingtunnel.client.util.newDirectSocket
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 上游 SOCKS5。SPP 模式是本机 socks5_client；SOCKS5 模式是远端代理。 */
data class SocksUpstream(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)

/**
 * 本地 SOCKS5 分流代理，位于 hev-socks5-tunnel 与上游 SOCKS5 之间。
 *
 * TUN 始终全量抓包（0.0.0.0/0），所有「直连 vs 代理」决策都在本类用户态完成，
 * 因此不存在 VpnService 路由表 Binder parcel 尺寸限制（Android 15 NetworkMonitor
 * TransactionTooLargeException），CN 集合零近似、零膨胀：
 *
 * - CONNECT 携带域名且命中 [directDomains]：本进程直连（App 自身被排除在 VPN 外，
 *   socket 天然走物理网络），域名由本地解析；
 * - CONNECT 携带 IP 字面量（App 自带 DoH/自建 DNS 解析后直连的场景）：命中
 *   [directIpv4Cidrs]/[directIpv6Cidrs]（精确 chnroute）或 [bypassPrivate] 的
 *   私有/CGNAT 段时本进程直连；
 * - 其它 CONNECT：转发给 [upstream]（本机 SPP 或远端 SOCKS5），域名型请求仍由
 *   服务端解析（mapdns 防 DNS 泄漏链路不变）；
 * - UDP ASSOCIATE：逐报文解析 SOCKS5 目标，直连目标由本进程 DatagramSocket 直发，
 *   其余经上游 UDP 中继；上游中继按需懒建立，纯直连会话零上游连接。
 *   直连本地失败（域名解析失败/socket 异常）时与 TCP 一样短期惩罚并立即改经上游，
 *   不静默丢报文。上游 ASSOCIATE 失败由该会话自己指数退避重试（全局只保留建链
 *   错开，避免 N 个会话同时重连），退避期间报文**延迟重试**而非丢弃——否则一个
 *   会话失败会连带静默丢掉其它会话新建 QUIC 流的握手包，表现为视频点开播不了。
 *   上游中继失效（发送异常）即拆链，下一包懒重建自愈。
 *   域名直连的本地 DNS 解析在专用有界单线程上异步执行，弱网下慢解析不会阻塞
 *   run() 单循环而冻住其它 UDP 流量；解析队列堆积时新报文直接回退上游。
 *   系统解析无原生超时，统一以 2.5s 预算兜底（TCP 快速回退代理、UDP 惩罚并回退）；
 *   同一 host 在解析期间只有一个 owner 任务，突发报文合并待发、解析后排空，
 *   防止一个慢 host 占满解析队列连累其它域名直连。
 *   代理报文只入有界队列，由每会话单写线程负责懒建立 ASSOCIATE 与发送——
 *   建链含 TCP connect 与 SOCKS 握手读（各有秒级预算，静默对端不再无限挂起），
 *   绝不同步占用 run() 派发循环，上游卡住期间直连 UDP 照常收发；队列背压按 UDP
 *   语义丢尾包，握手失败后退避重试（不再因冷却而作废整批报文）。
 *
 * 纯阻塞 IO + 守护线程，生命周期与 VPN 数据面一致。
 * 所有上游/直连 TCP 连接启用激进 keepalive（30s 起探），防移动 NAT 静默回收造成假死。
 */
class RuleSocksServer(
    private val upstream: SocksUpstream,
    private val directDomains: Set<String>,
    /** 命中即本地直连的 IPv4 CIDR（精确 chnroute，无块扩展）。 */
    private val directIpv4Cidrs: List<String> = emptyList(),
    /** 命中即本地直连的 IPv6 CIDR（精确 chnroute）。 */
    private val directIpv6Cidrs: List<String> = emptyList(),
    /** 把私有/保留网段（RFC1918、CGNAT、ULA 等）也纳入直连（绕过局域网）。 */
    private val bypassPrivate: Boolean = false,
    /**
     * 并发客户端连接上限（含尚未完成握手的）。
     *
     * 线程池为无界 cached pool、每条会话至少占 client + target 两个 fd：Android 单进程
     * fd 软限通常 1024，取 256 给 UDP 中继、文件与日志留一半余量。达到上限时新连接被
     * 立即关闭（背压），不再进握手——否则扫描类/DoH 类 App 或异常重连风暴能把线程和
     * fd 吃干，表现为整个代理（含已建立会话）一起瘫痪。
     *
     * 可注入仅为单测断言上限生效，生产保持默认。
     */
    private val maxClients: Int = MAX_CONCURRENT_CLIENTS,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val relays = mutableListOf<DatagramSocket>()
    private val children = mutableListOf<Socket>()

    /**
     * 存活的 UDP ASSOCIATE 会话数。
     *
     * internal 仅供单测/E2E 断言「对端关闭控制连接后关联被回收」——此前关联只靠本地
     * isClosed 判断存活，对端先关就会静默泄漏一个线程加 4 个 socket。
     */
    internal val activeAssociations = java.util.concurrent.atomic.AtomicInteger(0)
    private val prewarmPool = ConcurrentLinkedQueue<Socket>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "rule-socks").apply { isDaemon = true }
    }

    /**
     * 承载阻塞式系统 DNS 查询的线程池：查询本身无法设置超时（getaddrinfo 不响应中断），
     * 用独立线程 + Future 预算来限制等待；弱网下超时的查询线程会作为 daemon 残留到自行返回。
     */
    private val dnsLookupPool = Executors.newCachedThreadPool { r ->
        Thread(r, "dns-lookup").apply { isDaemon = true }
    }

    private val classifier = DirectClassifier(
        directDomains = directDomains,
        directIpv4Cidrs = directIpv4Cidrs,
        directIpv6Cidrs = directIpv6Cidrs,
        bypassPrivate = bypassPrivate,
    )

    /** 并发上限命中只打一次日志，避免拒绝期间日志被刷屏。 */
    private val capLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 直连失败短期惩罚缓存（实例级：网络切换/会话停止随本对象一起丢弃）。
     * 有硬上限：UDP 惩罚键含端口，唯一失败目标的基数不受控，长会话不能无界增长。
     */
    private val directPenaltyCache = BoundedTtlCache<Unit>(DIRECT_PENALTY_CACHE_MAX)

    /** 直连域名 DNS 缓存（实例级，TTL 60s；网络切换后随会话重建清空），LRU 有上限。 */
    private val dnsCache =
        BoundedTtlCache<List<InetAddress>>(DNS_CACHE_MAX, accessOrder = true)

    /**
     * 上游建链的最早时刻（实例级：网络切换/会话停止随本对象一起丢弃）。
     *
     * 只用于把多个 UDP 会话的 connect 错开，**不是**丢弃窗口：上游不可用时会让
     * 报文延迟重试而不会作废。此前这里是"冷却窗内所有会话取出即弃"，一个会话
     * 失败一次就能让随后数秒新建的 QUIC 流（点开视频起播）握手包全丢。
     */
    private val upstreamNextConnectAtMs = AtomicLong(0)
    /** 保护 [upstreamNextConnectAtMs] 预约的监视器。 */
    private val upstreamConnectGate = Any()

    val port: Int? get() = server?.localPort

    fun start() {
        if (running.getAndSet(true)) return
        val s = ServerSocket(0, 512, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute { acceptLoop(s) }
        replenishPrewarmPool()
        Log.i(TAG, "rule socks proxy listening on 127.0.0.1:${s.localPort}, ${classifier.summary()}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        while (prewarmPool.isNotEmpty()) {
            runCatching { prewarmPool.poll()?.close() }
        }
        synchronized(children) {
            children.toList().forEach { runCatching { it.close() } }
            children.clear()
        }
        synchronized(relays) {
            relays.toList().forEach { runCatching { it.close() } }
            relays.clear()
        }
        directPenaltyCache.clear()
        dnsCache.clear()
        dnsLookupPool.shutdownNow()
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                // stop() 关闭监听套接字是预期退出；running 期间的异常（EMFILE fd 耗尽、
                // 瞬时内核错误等）绝不能杀死监听——否则整个代理要等到 VPN 重建才能恢复。
                if (!running.get()) return
                Log.w(TAG, "accept failed, retry after ${ACCEPT_RETRY_BACKOFF_MS}ms: ${e.message}")
                try {
                    Thread.sleep(ACCEPT_RETRY_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            val active = synchronized(children) { children.size }
            if (active >= maxClients) {
                // 达到并发上限：直接断掉，不进握手。握手阶段要占线程与 10s 读预算，
                // 让它们进来等于把"限流"变成"更容易耗尽"。短暂退避避免 accept 空转。
                if (capLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "client limit reached ($maxClients), rejecting new connections until some drain")
                }
                runCatching { client.close() }
                try {
                    Thread.sleep(ACCEPT_RETRY_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            capLogged.set(false)
            synchronized(children) { children.add(client) }
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            // 监听 127.0.0.1 但设备上任何 app 都能连入：连上后不发/发一半 SOCKS 握手
            // 就静默挂住的连接，会永久占一个转发线程和 fd（线程池无界，可被慢慢耗尽）。
            // 给整个握手阶段加读预算，超时即按坏连接关闭；进入数据转发前由各 handler 复位。
            client.soTimeout = CLIENT_HANDSHAKE_TIMEOUT_MS.toInt()
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            if (!Socks5Codec.acceptMethod(input, out)) return
            val req = try {
                Socks5Codec.readRequest(input)
            } catch (e: Exception) {
                Log.w(TAG, "bad request: ${e.message}")
                return
            }
            when (req.command) {
                Socks5Codec.CMD_CONNECT.toInt() -> handleConnect(req, input, out, client)
                Socks5Codec.CMD_UDP_ASSOCIATE.toInt() -> handleUdpAssociate(out, client)
                else -> out.write(Socks5Codec.failureReply(Socks5Codec.REP_COMMAND_NOT_SUPPORTED))
            }
        } catch (e: Exception) {
            Log.v(TAG, "client ended: ${e.message}")
        } finally {
            runCatching { client.close() }
            synchronized(children) { children.remove(client) }
        }
    }

    /**
     * SOCKS5 请求目标是否应该本地直连（域名规则 / 精确 CIDR / 私有段）。
     * internal 以便单测直接锁定分流判定，而不必依赖真实连通性。
     */
    internal fun shouldDirect(endpointHost: String, atyp: Int): Boolean = when (atyp) {
        Socks5Codec.ATYP_DOMAIN.toInt() -> classifier.isDirectDomain(endpointHost)
        Socks5Codec.ATYP_IPV4.toInt(),
        Socks5Codec.ATYP_IPV6.toInt() -> classifier.isDirectIp(endpointHost)
        else -> false
    }

    private fun handleConnect(
        req: Socks5Codec.Request,
        input: DataInputStream,
        out: OutputStream,
        client: Socket,
    ) {
        val ruleDirect = shouldDirect(req.host, req.atyp)
        val started = System.currentTimeMillis()

        val inDirectPenalty = ruleDirect && isDirectPenalized(req.host)
        var directFailReason: String? = null
        var target: Socket? = null
        if (ruleDirect && !inDirectPenalty) {
            try {
                target = openConnected(req.host, req.port)
            } catch (e: Exception) {
                directFailReason = e.message ?: e.javaClass.simpleName
                markDirectPenalized(req.host)
                Log.i(TAG, "direct ${req.host}:${req.port} failed, penalized for ${DIRECT_PENALTY_MS / 1000}s, fallback to proxy: $directFailReason")
            }
        } else if (inDirectPenalty) {
            directFailReason = "penalized (previous direct failed)"
        }
        val usedDirect = target != null

        if (target == null) {
            target = try {
                connectUpstream(req)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "connect ${req.host}:${req.port} (direct=$ruleDirect) failed: $reason")
                recordEvent(
                    req,
                    usedDirect,
                    false,
                    directFailReason?.let { "direct: $it; " }.orEmpty() + reason,
                    started,
                )
                runCatching {
                    out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                    out.flush()
                }
                return
            }
        }

        // pipe 内部两个方向结束时会各自关闭两端 socket；
        // 这里不能在 finally 里提前 close，否则转发线程刚启动就被掐断。
        recordEvent(
            req,
            usedDirect,
            true,
            directFailReason?.let { "direct failed -> proxy ($it)" },
            started,
        )
        try {
            out.write(Socks5Codec.successReply("0.0.0.0", 0))
            out.flush()
        } catch (e: Exception) {
            // 客户端可能在我们建连期间已经 RST 走人：target 已连接成功，
            // 不关掉就是永久 fd 泄漏（handleClient 的 finally 只关 client）。
            Log.v(TAG, "client gone before CONNECT reply, closing target: ${e.message}")
            runCatching { target.close() }
            return
        }
        client.soTimeout = 0 // 进入双向转发：copyDirection 的读必须无限阻塞
        awaitPipe(client, target)
    }

    /**
     * 连上上游并完成认证；失败抛异常，调用方负责后续 CONNECT / UDP ASSOCIATE。
     * 返回的 socket 带有 [UPSTREAM_HANDSHAKE_TIMEOUT_MS] 的读超时（覆盖紧接的
     * CONNECT/ASSOCIATE 应答读）；作为长连接中继/看门狗使用前调用方必须复位 soTimeout=0。
     */
    private fun openUpstream(): Socket {
        // 必须绕过系统 ProxySelector：否则系统级 SOCKS 代理会劫持对上游的连接
        val socket = newDirectSocket()
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = TCP_SOCKET_BUFFER
            socket.receiveBufferSize = TCP_SOCKET_BUFFER
            socket.connect(InetSocketAddress(upstream.host, upstream.port), CONNECT_TIMEOUT_MS)
            // connect 成功不代表对端会完成 SOCKS 协商：静默对端（accept 后无任何响应）
            // 下默认 read 会无限阻塞。给整个握手阶段的读加预算，按失败快速走重试/冷却。
            socket.soTimeout = UPSTREAM_HANDSHAKE_TIMEOUT_MS.toInt()
            val upOut = socket.getOutputStream()
            val upIn = DataInputStream(socket.getInputStream())
            if (!Socks5Codec.authenticateClient(upIn, upOut, upstream.username, upstream.password)) {
                throw java.io.IOException("upstream auth rejected")
            }
            // 上游连接（TCP 中继 / 预热池 / UDP ASSOCIATE 控制连接）启用激进 keepalive：
            // 防止移动 NAT 静默回收导致长连接假死（UDP 看门狗依赖 ctrl 读失败才能感知）。
            socket.enableMobileKeepAlive()
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** 补充上游预热池（最多 PREWARM_POOL_SIZE 条空闲认证就绪连接）。 */
    private fun replenishPrewarmPool() {
        if (!running.get()) return
        pool.execute {
            while (running.get() && prewarmPool.size < PREWARM_POOL_SIZE) {
                try {
                    val prewarmed = openUpstream()
                    if (running.get()) {
                        prewarmPool.offer(prewarmed)
                    } else {
                        runCatching { prewarmed.close() }
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    /** 从预热池取出一个可用连接，或实时建连。 */
    private fun acquireUpstream(): Socket {
        while (prewarmPool.isNotEmpty()) {
            val sock = prewarmPool.poll() ?: break
            if (!sock.isClosed && sock.isConnected) {
                replenishPrewarmPool()
                return sock
            }
            runCatching { sock.close() }
        }
        val sock = openUpstream()
        replenishPrewarmPool()
        return sock
    }

    /** 连接上游并完成 SOCKS5 CONNECT；优先复用已预热认证的连接。 */
    private fun connectUpstream(req: Socks5Codec.Request): Socket {
        var socket = acquireUpstream()
        try {
            val upOut = socket.getOutputStream()
            val upIn = DataInputStream(socket.getInputStream())
            upOut.write(req.raw)
            upOut.flush()
            if (Socks5Codec.readReply(upIn) == null) {
                throw java.io.IOException("upstream CONNECT rejected/timeout")
            }
            socket.soTimeout = 0 // 进入长连接中继：copyDirection 的读必须无限阻塞
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            // 预热连接可能已被服务器因空闲超时断开，重试一次新建连
            socket = openUpstream()
            val upOut = socket.getOutputStream()
            val upIn = DataInputStream(socket.getInputStream())
            upOut.write(req.raw)
            upOut.flush()
            if (Socks5Codec.readReply(upIn) == null) {
                runCatching { socket.close() }
                throw java.io.IOException("upstream CONNECT rejected/timeout")
            }
            socket.soTimeout = 0 // 同上：长连接中继阶段取消握手读预算
            return socket
        }
    }

    /**
     * 双通道独立转发：
     * client -> target 和 target -> client 拆为两个完全独立的单向通道。
     * 当任一方向读到 EOF (FIN) 时，立即将对端输出半关闭 (shutdownOutput())，
     * 但保留反向继续传输的能力（严格兼容 HTTP/2 和 gRPC 双向流）。
     * 当双向转发全部完成，或者发生底层异常时，统一关闭两端 Socket。
     */
    private fun awaitPipe(a: Socket, b: Socket) {
        runCatching {
            a.sendBufferSize = TCP_SOCKET_BUFFER
            a.receiveBufferSize = TCP_SOCKET_BUFFER
            b.sendBufferSize = TCP_SOCKET_BUFFER
            b.receiveBufferSize = TCP_SOCKET_BUFFER
        }
        val latch = java.util.concurrent.CountDownLatch(2)

        val forwardDirection = { from: Socket, to: Socket ->
            try {
                copyDirection(from, to)
            } finally {
                // 该方向读取结束，通知目标端此方向已无新数据 (发送 TCP FIN)
                runCatching {
                    if (!to.isClosed && !to.isOutputShutdown) {
                        to.shutdownOutput()
                    }
                }
                latch.countDown()
            }
        }

        pool.execute { forwardDirection(a, b) }
        pool.execute { forwardDirection(b, a) }

        // 等待两个单向通道全部完成（或异常退出）
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private fun recordEvent(
        req: Socks5Codec.Request,
        direct: Boolean,
        success: Boolean,
        reason: String?,
        startedMs: Long,
    ) {
        runCatching {
            ProxyEventBus.record(
                host = req.host,
                port = req.port,
                direct = direct,
                success = success,
                reason = reason,
                durationMs = (System.currentTimeMillis() - startedMs).toInt(),
            )
        }
    }

    /**
     * 解析 [host] 的全部地址并并发尝试连接（RFC 8305 Happy Eyeballs 竞速）。
     * 首选 IPv4，若首个地址在 250ms 内未连接成功，立即发起下一个地址的尝试。
     * 任一成功即返回已连接的 Socket，其余连接被快速取消并关闭。
     */
    private fun openConnected(host: String, port: Int): Socket {
        val addresses = resolveHost(host)
        if (addresses.isEmpty()) {
            throw java.net.UnknownHostException(host)
        }
        if (addresses.size == 1) {
            val addr = addresses[0]
            // 绕过系统 ProxySelector：直连目标绝不能被系统级 SOCKS 代理劫持
            // （IPv4 被代转发看似成功，IPv6 被拒发后直接 EOF）
            val s = newDirectSocket()
            s.tcpNoDelay = true
            s.sendBufferSize = TCP_SOCKET_BUFFER
            s.receiveBufferSize = TCP_SOCKET_BUFFER
            s.connect(InetSocketAddress(addr, port), ADDR_CONNECT_TIMEOUT_MS.toInt())
            s.enableMobileKeepAlive()
            return s
        }

        // 多地址：Happy Eyeballs 并发竞速
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        val completionQueue = java.util.concurrent.LinkedBlockingQueue<Result<Socket>>()
        val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        var dispatched = 0
        var completed = 0

        for (addr in addresses) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0 && dispatched > 0) break

            val s = newDirectSocket()
            activeSockets.add(s)
            dispatched++

            pool.execute {
                try {
                    s.tcpNoDelay = true
                    s.sendBufferSize = TCP_SOCKET_BUFFER
                    s.receiveBufferSize = TCP_SOCKET_BUFFER
                    val timeout = (deadline - System.currentTimeMillis()).coerceIn(100, ADDR_CONNECT_TIMEOUT_MS)
                    s.connect(InetSocketAddress(addr, port), timeout.toInt())
                    s.enableMobileKeepAlive()
                    completionQueue.offer(Result.success(s))
                } catch (e: Exception) {
                    runCatching { s.close() }
                    activeSockets.remove(s)
                    completionQueue.offer(Result.failure(e))
                }
            }

            // 给予先发地址 250ms 优势窗口；如果 250ms 内有任一成功，直接返回
            val res = completionQueue.poll(HAPPY_EYEBALLS_HEAD_START_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (res != null) {
                completed++
                if (res.isSuccess) {
                    val winner = res.getOrThrow()
                    activeSockets.remove(winner)
                    activeSockets.forEach { runCatching { it.close() } }
                    return winner
                }
            }
        }

        // 等待所有已派发连接出结果
        var lastError: Exception? = null
        while (completed < dispatched) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val res = completionQueue.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            completed++
            if (res.isSuccess) {
                val winner = res.getOrThrow()
                activeSockets.remove(winner)
                activeSockets.forEach { runCatching { it.close() } }
                return winner
            } else {
                lastError = res.exceptionOrNull() as? Exception
            }
        }

        activeSockets.forEach { runCatching { it.close() } }
        throw lastError ?: java.io.IOException("cannot connect to $host:$port (Happy Eyeballs timeout)")
    }

    private fun copyDirection(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            val input = from.getInputStream()
            val out = to.getOutputStream()
            while (running.get() && !from.isClosed && !to.isClosed) {
                val n = input.read(buf)
                if (n < 0) break // 读到 EOF
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
            // 对端或本地连接异常中断，快速关闭以唤醒对向读写
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    // ============================== UDP ==============================

    private fun handleUdpAssociate(out: OutputStream, control: Socket) {
        // 控制连接进入 ASSOCIATE 长生命周期（splitter 不读它，只靠 isClosed/EOF 感知死亡）：
        // 取消握手读预算，避免后续任何复用读取被 10s 超时误杀。
        control.soTimeout = 0
        // 面向 hev 的中继（hev 把报文发到这里）；上游/直连中继在 UdpSplitter 内按需建立
        val clientRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val splitter = UdpSplitter(clientRelay, control, udpDeps())
        synchronized(relays) { relays.add(clientRelay) }
        activeAssociations.incrementAndGet()
        // 对端（hev 或本地 socks 客户端）关闭控制连接时必须有人收摊：run() 的存活条件
        // 只看**本地** isClosed，而 control 全程无人读——对端 FIN/RST 后本端 isClosed
        // 仍为 false，关联会带着线程和 4 个 socket（clientRelay / direct v4+v6 / upRelay）
        // 永久挂着，每次 VPN 重建累积一份。这里起读 ctrl 的看门狗：EOF 或异常即关
        // relay + ctrl，打断派发循环（与上游侧 watchUpstreamControl 同一套思路）。
        val watchdog = Thread({
            try {
                // ASSOCIATE 后控制连接不应再有数据，个别实现发的 keepalive 字节直接忽略
                while (control.getInputStream().read() >= 0) { /* keepalive 字节，忽略 */ }
            } catch (_: Exception) {
            } finally {
                runCatching { clientRelay.close() }
                runCatching { control.close() }
            }
        }, "udp-control-watchdog").apply { isDaemon = true }
        try {
            clientRelay.receiveBufferSize = UdpTuning.SOCKET_BUFFER
            out.write(Socks5Codec.successReply("127.0.0.1", clientRelay.localPort))
            out.flush()
            Log.i(TAG, "udp splitter relay on 127.0.0.1:${clientRelay.localPort}, upstream=$upstream")
            watchdog.start()
            splitter.run()
        } catch (e: Exception) {
            Log.w(TAG, "udp associate failed: ${e.message}")
        } finally {
            splitter.close()
            synchronized(relays) { relays.remove(clientRelay) }
            runCatching { clientRelay.close() }
            // handleClient 的 finally 会关 control，看门狗的阻塞 read 随即抛异常退出
            activeAssociations.decrementAndGet()
        }
    }


    /**
     * 组装 [UdpSplitter] 的依赖：把"内层类隐式访问外层状态"改成显式注入，
     * 这样 UDP 中继的行为变化只能来自这里列出的注入点，便于单测替换。
     */
    private fun udpDeps() = UdpDeps(
        upstream = upstream,
        running = running,
        relays = relays,
        pool = pool,
        nextConnectAtMs = upstreamNextConnectAtMs,
        connectGate = upstreamConnectGate,
        shouldDirect = { host, atyp -> shouldDirect(host, atyp) },
        isDirectPenalizedKey = ::isDirectPenalizedKey,
        penalizeDirectKey = ::penalizeDirectKey,
        udpDirectKey = ::udpDirectKey,
        upstreamRelayAddress = ::upstreamRelayAddress,
        resolveHost = ::resolveHost,
        openUpstream = ::openUpstream,
        isLoopback = Companion::isLoopback,
    )

    /**
     * SOCKS5 UDP ASSOCIATE 服务器回复的中继地址：
     * - 本机 SPP 固定走 127.0.0.1；
     * - 远端 SOCKS5：若服务器回复了通配地址（0.0.0.0 / ::）、本地回环（127.x）
     *   或私有网段（10.x, 172.16-31.x, 192.168.x 等内网 NAT 地址），
     *   手机作为公网客户端无法直连该私有地址，必须改用与控制连接相同的 [upstream.host]。
     */
    internal fun upstreamRelayAddress(boundHost: String, boundPort: Int): InetSocketAddress {
        if (isLoopback(upstream.host)) {
            return InetSocketAddress("127.0.0.1", boundPort)
        }
        val useUpstreamHost = boundHost.isBlank() ||
            boundHost == "0.0.0.0" ||
            boundHost == "::" ||
            isUnroutableFromRemote(boundHost)
        val host = if (useUpstreamHost) upstream.host else boundHost
        return InetSocketAddress(host, boundPort)
    }

    private fun isUnroutableFromRemote(host: String): Boolean {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress
    }

    private fun isDirectPenalized(key: String): Boolean =
        directPenaltyCache.get(key, System.currentTimeMillis()) != null

    private fun markDirectPenalized(key: String) {
        directPenaltyCache.put(key, Unit, System.currentTimeMillis() + DIRECT_PENALTY_MS)
    }

    // ---- UDP 直连惩罚（与 TCP 共用缓存；udp: 前缀避免与 TCP 的裸 host 键冲突） ----

    internal fun udpDirectKey(host: String, port: Int): String = "udp:$host:$port"

    internal fun isDirectPenalizedKey(key: String): Boolean = isDirectPenalized(key)

    internal fun penalizeDirectKey(key: String) = markDirectPenalized(key)

    /**
     * 本地解析直连目标（App 自身被排除 VPN，走物理网络的系统解析器）。
     * 结果缓存 [DNS_CACHE_TTL_MS]；失败时在 TTL 内沿用上次的成功结果。
     * IPv4 排在前面（Happy Eyeballs 由调用方再做竞速）。
     *
     * 系统解析没有原生超时：弱网/无响应 DNS 下 getaddrinfo 可能阻塞数十秒，
     * 这里用 [DNS_RESOLVE_BUDGET_MS] 预算兜底——超时按失败处理（TCP 快速回退
     * 代理、UDP 惩罚目标并回退），不让任何一条流程被解析无限期拖住。
     */
    private fun resolveHost(host: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache.get(host, now)?.let { return it }
        val resolved = runWithBudget(dnsLookupPool, DNS_RESOLVE_BUDGET_MS) {
            InetAddress.getAllByName(host).toList()
        }.orEmpty()
        if (resolved.isNotEmpty()) {
            val ordered = resolved.sortedBy { if (it is Inet6Address) 1 else 0 }
            dnsCache.put(host, ordered, now + DNS_CACHE_TTL_MS)
            return ordered
        }
        // 解析失败时沿用上次的成功结果（无视过期，冷启动/被 LRU 淘汰后没有则为空）
        return dnsCache.getStale(host).orEmpty()
    }

    internal companion object {
        const val TAG = "RuleSocksServer"
        const val CONNECT_TIMEOUT_MS = 3000

        /**
         * 客户端 SOCKS5 握手阶段（方法协商 + CONNECT/ASSOCIATE 请求）读预算（毫秒）。
         * 本地 app 连入后静默不发数据时，最多占一个线程/fd 这么久即被关闭，
         * 防止无界 cached 线程池被慢速连接耗尽；正常 hev 握手在毫秒级完成。
         */
        const val CLIENT_HANDSHAKE_TIMEOUT_MS = 10_000L

        /** accept() 瞬时失败（如 EMFILE）后重试前的退避（毫秒），避免 busy loop。 */
        const val ACCEPT_RETRY_BACKOFF_MS = 200L
        /**
         * 并发客户端连接上限：每条会话至少占 client + target 两个 fd，Android 单进程
         * fd 软限通常 1024，取 256 给 UDP 中继/文件/日志留一半余量。
         */
        const val MAX_CONCURRENT_CLIENTS = 256
        /** 单个地址的连接尝试上限，保证失败时秒级回退代理，不让用户等待。 */
        const val ADDR_CONNECT_TIMEOUT_MS = 1500L
        const val BUFFER_SIZE = 64 * 1024
        const val TCP_SOCKET_BUFFER = 256 * 1024

        /** 直连失败的惩罚时长（毫秒）：此时间内该目标直接走代理，消除并发或连续请求的重复回退等待。 */
        const val DIRECT_PENALTY_MS = 180_000L // 3 分钟

        /** 直连惩罚缓存硬上限：UDP 惩罚键含端口，防止长会话内唯一失败目标堆积无界增长。 */
        const val DIRECT_PENALTY_CACHE_MAX = 1024

        /** 直连 DNS 缓存硬上限（LRU）：超出淘汰最久未访问的域名解析。 */
        const val DNS_CACHE_MAX = 512

        /**
         * 上游 SOCKS 握手阶段单次读预算（毫秒）：TCP connect 成功后对端可能静默
         * （accept 后不回协商/应答），默认 read 无限阻塞，按超时即失败快速走重试/冷却。
         */
        const val UPSTREAM_HANDSHAKE_TIMEOUT_MS = 3_000L

        /** RFC 8305 Happy Eyeballs 先发优势窗口（毫秒）。 */
        const val HAPPY_EYEBALLS_HEAD_START_MS = 250L
        /** 上游代理预热连接池上限。 */
        const val PREWARM_POOL_SIZE = 4
        /** 本地 DNS 缓存有效时长（毫秒）。 */
        const val DNS_CACHE_TTL_MS = 60_000L

        /** 单次系统 DNS 查询的等待预算（毫秒）：超时按解析失败处理，快速回退代理。 */
        const val DNS_RESOLVE_BUDGET_MS = 2_500L

        /**
         * 在 [executor] 上执行 [work]，最多等待 [timeoutMs]；成功返回结果，
         * 超时取消任务并返回 null（不响应中断的阻塞调用其线程会残留），
         * work 抛异常或等待被中断也返回 null。internal 以便单测锁定预算行为。
         */
        internal fun <T> runWithBudget(
            executor: java.util.concurrent.ExecutorService,
            timeoutMs: Long,
            work: () -> T,
        ): T? {
            val future = executor.submit(work)
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                null
            } catch (_: java.util.concurrent.ExecutionException) {
                null
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                null
            }
        }

        fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}

// ---- 移动网络 TCP keepalive ----
// 系统默认 SO_KEEPALIVE 探测要空闲 2 小时才开始，远大于移动 NAT 的 2~5 分钟空闲回收：
// 长连接（TCP 中继、UDP ASSOCIATE 控制连接）会在 NAT 静默回收后变成死连接且收不到 FIN，
// UDP 看门狗会永久阻塞在 ctrl read 上、relay 永不自愈。用激进参数（30s 起探）保持映射、
// 最快约 60s 判死，代价只是长连接期间每 30s 一个几十字节探测包（网络体验优先，可接受）。

private const val IPPROTO_TCP = 6
private const val TCP_KEEPIDLE_LINUX = 4
private const val TCP_KEEPINTVL_LINUX = 5
private const val TCP_KEEPCNT_LINUX = 6

private const val KEEPALIVE_IDLE_SEC = 30
private const val KEEPALIVE_INTERVAL_SEC = 10
private const val KEEPALIVE_COUNT = 3

/**
 * 对已连接 socket 启用移动友好的 TCP keepalive。必须在 connect 成功后调用（需要有效 fd）。
 * 标准 SO_KEEPALIVE 全平台生效；Linux 探测参数经反射调用 android.system.Os 设置
 * （minSdk 26 无法直接引用 API 31+ 的 jdk.net.ExtendedSocketOptions）。
 * 非 Android 平台（JVM 单测）反射失败静默忽略，只保留 SO_KEEPALIVE，绝不影响连接。
 */
internal fun Socket.enableMobileKeepAlive() {
    runCatching {
        keepAlive = true
        val osClass = Class.forName("android.system.Os")
        val getImpl = Socket::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }
        val impl = getImpl.invoke(this)
        val getFd = impl.javaClass.getDeclaredMethod("getFileDescriptor").apply { isAccessible = true }
        val fd = getFd.invoke(impl) as java.io.FileDescriptor
        val setsockoptInt = osClass.getMethod(
            "setsockoptInt",
            java.io.FileDescriptor::class.java, Integer.TYPE, Integer.TYPE, Integer.TYPE,
        )
        setsockoptInt.invoke(null, fd, IPPROTO_TCP, TCP_KEEPIDLE_LINUX, KEEPALIVE_IDLE_SEC)
        setsockoptInt.invoke(null, fd, IPPROTO_TCP, TCP_KEEPINTVL_LINUX, KEEPALIVE_INTERVAL_SEC)
        setsockoptInt.invoke(null, fd, IPPROTO_TCP, TCP_KEEPCNT_LINUX, KEEPALIVE_COUNT)
    }
}
