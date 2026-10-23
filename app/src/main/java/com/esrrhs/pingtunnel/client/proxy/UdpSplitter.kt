package com.esrrhs.pingtunnel.client.proxy

import android.util.Log
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// 与原 RuleSocksServer 保持同一个 log tag：日志页与排障习惯按 tag 过滤。
private const val TAG = "RuleSocksServer"

/** UDP 侧的调参常量（从 RuleSocksServer 拆出，UDP 中继与本文件外部共用）。 */
internal object UdpTuning {
    const val TIMEOUT_MS = 1000
    const val BUFFER_SIZE = 64 * 1024
    const val SOCKET_BUFFER = 512 * 1024
    /** 与 hev-socks5-tunnel 的 UDP_BUF_SIZE 一致，回程整包（含 SOCKS 头）不能超过它。 */
    const val HEV_RECV_LIMIT = 1500
    const val UPSTREAM_QUEUE_CAPACITY = 64
    const val UPSTREAM_WRITER_POLL_MS = 500L
    /**
     * 本会话上游建链失败后的退避：指数增长到 [UPSTREAM_BACKOFF_MAX_MS]，成功即清零。
     *
     * 刻意做成**每会话**的：此前是一次失败设一个实例级 5s 冷却、冷却窗内所有会话的
     * 报文取出即弃，于是后台任一 UDP 会话失败就会让随后 5 秒新建的 QUIC 流握手包全丢。
     */
    const val UPSTREAM_BACKOFF_BASE_MS = 200L
    const val UPSTREAM_BACKOFF_MAX_MS = 5_000L
    /**
     * 全局建链间隔：N 个会话同时失败时把各自的 connect 错开（防雪崩），只延迟不丢弃。
     * 与退避的区别：它是"最早何时可以发起"，不是"该报文作废"。
     */
    const val UPSTREAM_CONNECT_SPACING_MS = 200L
    /** 单包最多尝试建链几次后放弃，避免上游静默时写线程被一包长期占住。 */
    const val UPSTREAM_MAX_ATTEMPTS = 2
    /**
     * 回程超 [HEV_RECV_LIMIT] 的报文会被 hev 截断（QUIC 直接坏掉）。QUIC 会持续
     * 触发这条告警，一次性开关会让"之后还在不在发生"永远不可见，改成采样计数。
     */
    const val OVERSIZE_LOG_EVERY = 64
    const val RESOLVE_QUEUE_CAPACITY = 16
    const val PENDING_PER_HOST = 32
}

/**
 * 单个 UDP ASSOCIATE 会话的分流中继（从 RuleSocksServer 拆出以便单独演进/阅读）。
 *
 * - hev -> 本进程：解析 SOCKS5 UDP 头，按 [UdpDeps.shouldDirect] 判定：
 *   直连报文经本地 wildcard DatagramSocket 直发物理网络（App 已被排除 VPN），
 *   代理报文整包转发上游 UDP 中继（上游中继懒建立，纯直连会话不产生上游连接）；
 * - 目标/上游 -> 本进程：封装（直连）或透传（上游已封装）SOCKS5 UDP 头后回给 hev。
 *
 * 依赖全部经 [UdpDeps] 显式注入：本类不持有 RuleSocksServer，也就不存在"内层类
 * 悄悄摸外层状态"的耦合，行为变化只能来自注入点。
 */
internal class UdpSplitter(
    private val clientRelay: DatagramSocket,
    private val control: Socket,
    private val deps: UdpDeps,
) {
    private val gate = Any()
    private var upControl: Socket? = null
    private var upRelay: DatagramSocket? = null
    private var upstreamRelay: InetSocketAddress? = null
    private var directV4: DatagramSocket? = null
    private var directV6: DatagramSocket? = null

    /**
     * 域名直连解析的专用有界单线程执行器（懒创建：纯代理/纯 IP 会话零线程）。
     * 1 线程串行 + 有界队列天然限流，避免慢解析堆积也避免线程爆炸。
     */
    private var resolveExecutor: ThreadPoolExecutor? = null

    /**
     * 正在解析中的 host → 合并等待的原始报文队列（首个报文为 owner，自行提交解析任务）。
     * owner 完成后先摘除本标记再排空队列，避免新到达报文滞留。
     */
    private val inflightHosts =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

    /**
     * 待发上游的已封装报文有界队列：run()/解析线程只入队，
     * 由唯一的 [upstreamWriter] 懒建立 ASSOCIATE 并串行发送，
     * 建链与握手绝不阻塞 run() 派发循环（弱网/静默上游期间直连 UDP 不受影响）。
     */
    private val upstreamQueue = ArrayBlockingQueue<ByteArray>(UdpTuning.UPSTREAM_QUEUE_CAPACITY)
    private var upstreamWriter: Thread? = null
    private val upstreamQueueFullLogged = AtomicBoolean(false)

    /** RFC 1928：只与发起 ASSOCIATE 的客户端地址通信，首个报文锁定。 */
    private val peer = AtomicReference<InetSocketAddress>()

    fun run() {
        val buf = ByteArray(UdpTuning.BUFFER_SIZE)
        while (deps.running.get() && !control.isClosed && !clientRelay.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                clientRelay.soTimeout = UdpTuning.TIMEOUT_MS
                clientRelay.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (deps.running.get()) Log.w(TAG, "udp from-hev: ${e.message}")
                break
            }

            val sender = packet.socketAddress as? InetSocketAddress ?: continue
            val lockedPeer = peer.get()
            if (lockedPeer != null && lockedPeer != sender) continue

            val parsed = Socks5Codec.parseUdpPacket(packet.data, packet.length) ?: continue
            if (lockedPeer == null) peer.set(sender)

            val (endpoint, payloadOffset) = parsed
            val payloadLen = packet.length - payloadOffset

            // 与 TCP 对称：命中直连且不在惩罚窗内才走本地直发；
            // 直连本地失败或此前已被惩罚时，立即改经上游，不丢报文。
            val ruleDirect = deps.shouldDirect(endpoint.host, endpoint.atyp)
            val skipDirect = !ruleDirect ||
                deps.isDirectPenalizedKey(deps.udpDirectKey(endpoint.host, endpoint.port))

            if (skipDirect) {
                enqueueUpstream(packet.data, packet.length)
            } else if (endpoint.atyp == Socks5Codec.ATYP_DOMAIN.toInt()) {
                // 域名解析在弱网下可能慢：不做在 run() 单循环上（见 round6），
                // 且同一 host 在解析期间只允许一个 owner 任务——突发的其余报文合并入队，
                // 解析完成后统一派发，避免一个慢 host 占满有界解析队列、连累其它域名。
                val copy = packet.data.copyOf(packet.length)
                val pending = inflightHosts.putIfAbsent(endpoint.host, ConcurrentLinkedQueue())
                if (pending == null) {
                    dispatchResolveOwner(endpoint.host, copy)
                } else if (pending.size < UdpTuning.PENDING_PER_HOST) {
                    pending.add(copy)
                } else {
                    enqueueUpstream(copy, copy.size) // 单 host 待发堆积超限，溢出走上游
                }
            } else {
                // IP 字面量：本地 send 立即返回，保持同步快速路径。
                if (!sendDirect(endpoint, packet.data, payloadOffset, payloadLen)) {
                    enqueueUpstream(packet.data, packet.length)
                }
            }
        }
    }

    fun close() {
        inflightHosts.clear() // ASSOCIATE 拆除：合并等待的报文随之放弃
        upstreamQueue.clear() // 待发上游报文同样放弃
        synchronized(gate) {
            // 唤醒可能在 poll 等待的写线程；阻塞在经典 IO connect 中的写线程
            // 不响应中断，但 ensureUpstream 发布前会在本锁内复查关闭状态而自灭。
            upstreamWriter?.interrupt()
        }
        // 上游与直连 socket 的关闭/摘除都在 gate 内，和 ensureUpstream 的发布互斥：
        // 建链在 gate 外进行，本处不会被网络超时拖住，晚完成的握手也无法再发布泄漏。
        resetUpstream()
        synchronized(gate) {
            runCatching { directV4?.close() }
            runCatching { directV6?.close() }
            synchronized(deps.relays) {
                directV4?.let { deps.relays.remove(it) }
                directV6?.let { deps.relays.remove(it) }
            }
            directV4 = null
            directV6 = null
        }
        // 关闭解析执行器：正在进行的解析被中断，排队任务丢弃（ASSOCIATE 已拆除，无后续报文）。
        resolveExecutor?.let { exec ->
            resolveExecutor = null
            exec.shutdownNow()
            runCatching { exec.awaitTermination(200, TimeUnit.MILLISECONDS) }
        }
    }

    /** 懒创建域名直连解析执行器（gate 内）；失败返回 null（调用方直接走上游）。 */
    private fun obtainResolveExecutor(): ThreadPoolExecutor? = synchronized(gate) {
        resolveExecutor?.let { return it }
        if (!deps.running.get()) return null
        val exec = runCatching {
            ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(UdpTuning.RESOLVE_QUEUE_CAPACITY),
                { r -> Thread(r, "udp-direct-resolve").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )
        }.getOrNull() ?: return null
        resolveExecutor = exec
        exec
    }

    /**
     * 提交 host 的 owner 解析任务：任务内解析+直发首包（失败回退上游），
     * 随后先摘除 inflight 标记、再排空合并队列逐条走相同决策。
     * 提交被拒（解析队列满）或执行器不可用时放弃 owner 身份，首包直接走上游。
     */
    private fun dispatchResolveOwner(host: String, ownerCopy: ByteArray) {
        val executor = obtainResolveExecutor()
        var accepted = false
        if (executor != null) {
            try {
                executor.execute {
                    try {
                        val parsed = Socks5Codec.parseUdpPacket(ownerCopy, ownerCopy.size)
                        if (parsed != null) {
                            val (ep, off) = parsed
                            if (!sendDirect(ep, ownerCopy, off, ownerCopy.size - off)) {
                                enqueueUpstream(ownerCopy, ownerCopy.size)
                            }
                        }
                    } finally {
                        val queue = inflightHosts.remove(host)
                        queue?.let { drainPending(it) }
                    }
                }
                accepted = true
            } catch (_: RejectedExecutionException) {
                accepted = false
            }
        }
        if (!accepted) {
            // 拒绝窗口内可能已有报文合并进旧队列：摘标记并全部回退上游，不丢包。
            inflightHosts.remove(host)?.let { q ->
                while (true) {
                    val raw = q.poll() ?: break
                    enqueueUpstream(raw, raw.size)
                }
            }
            enqueueUpstream(ownerCopy, ownerCopy.size)
        }
    }

    private fun drainPending(queue: ConcurrentLinkedQueue<ByteArray>) {
        while (true) {
            val raw = queue.poll() ?: return
            val parsed = Socks5Codec.parseUdpPacket(raw, raw.size) ?: continue
            val (ep, off) = parsed
            if (!sendDirect(ep, raw, off, raw.size - off)) {
                enqueueUpstream(raw, raw.size)
            }
        }
    }

    /**
     * 整包（仍带 SOCKS 头）入队，交由 [upstreamWriter] 懒建立上游中继后发送。
     * 绝不阻塞调用线程：run() 派发循环与解析 owner 线程都可能调用，
     * 而建链（TCP connect 3s）+ SOCKS 握手读（各 3s 预算）在弱网下秒级耗时，
     * 同步等待会冻住同 ASSOCIATE 内的直连 UDP。队列满（建链长期卡住的背压）
     * 按 UDP 语义丢尾包，只打一次日志。
     */
    private fun enqueueUpstream(data: ByteArray, len: Int) {
        if (!deps.running.get() || control.isClosed) return
        synchronized(gate) {
            if (upstreamWriter == null) {
                if (!deps.running.get() || control.isClosed) return
                upstreamWriter = Thread({ upstreamWriteLoop() }, "udp-upstream-writer")
                    .apply { isDaemon = true }
                    .also { it.start() }
            }
        }
        if (!upstreamQueue.offer(data.copyOf(len)) &&
            upstreamQueueFullLogged.compareAndSet(false, true)
        ) {
            Log.w(TAG, "udp upstream queue full (${UdpTuning.UPSTREAM_QUEUE_CAPACITY}), dropping datagram")
        }
    }

    /**
     * 上游写线程唯一循环：串行消费队列、懒建立 ASSOCIATE、发送。
     * 建链失败由 [deliverUpstream] 按每会话退避等待重试（不再丢弃报文）；
     * 发送异常拆链，下一个出队报文懒重建。空队列周期 poll 以便及时退出。
     */
    private fun upstreamWriteLoop() {
        while (deps.running.get() && !control.isClosed) {
            val data = try {
                upstreamQueue.poll(UdpTuning.UPSTREAM_WRITER_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (data == null) continue
            if (!deliverUpstream(data)) {
                Log.w(
                    TAG,
                    "udp upstream unavailable after ${UdpTuning.UPSTREAM_MAX_ATTEMPTS} attempts, " +
                        "dropping datagram",
                )
            }
        }
    }

    /**
     * 把一包交给上游：未就绪时按退避**等待并重试**，而不是丢弃。
     *
     * 这条路径此前是"实例级冷却窗内取出即弃"：任一 UDP 会话建链失败会给**所有**
     * 会话设一个共享的 5s 冷却，冷却窗内每包都被静默丢掉。后果是后台某个 UDP 会话
     * 失败一次，随后 5 秒内新建的 QUIC 流（典型场景：点开视频起播）握手包全丢、
     * 播放起不来。现在退避是每会话的（A 失败不影响 B 的首次尝试），且只延迟不丢弃：
     * 上游真不可用时把重传交给 QUIC 自己，比静默黑洞更容易活下来。
     */
    private fun deliverUpstream(data: ByteArray): Boolean {
        var attempts = 0
        while (deps.running.get() && !control.isClosed) {
            val waitMs = waitBeforeAttemptMs()
            if (waitMs > 0) {
                runCatching { Thread.sleep(waitMs) }
                if (!deps.running.get() || control.isClosed) return false
            }
            if (!ensureUpstream()) {
                markAttemptFailed()
                attempts++
                if (attempts >= UdpTuning.UPSTREAM_MAX_ATTEMPTS) return false
                continue
            }
            val relay = upRelay ?: return false
            val target = upstreamRelay ?: return false
            try {
                relay.send(DatagramPacket(data, data.size, target))
                onAttemptSucceeded()
                return true
            } catch (e: Exception) {
                if (deps.running.get()) Log.w(TAG, "udp to-upstream: ${e.message}")
                // 上游会话已失效：拆链（receiveUpstreamLoop 随 socket 关闭退出），下一包懒重建
                resetUpstream()
                markAttemptFailed()
                attempts++
                if (attempts >= UdpTuning.UPSTREAM_MAX_ATTEMPTS) return false
            }
        }
        return false
    }

    /** 本会话下一次可以发起建链的时间戳（0 = 立即），仅写线程与等待逻辑访问。 */
    private val nextAttemptAtMs = AtomicLong(0)
    /** 连续失败次数，用于指数退避；成功即清零。仅写线程访问。 */
    private var attemptStreak = 0

    private fun markAttemptFailed() {
        attemptStreak++
        val shift = (attemptStreak - 1).coerceAtMost(4)
        val backoff = (UdpTuning.UPSTREAM_BACKOFF_BASE_MS shl shift)
            .coerceAtMost(UdpTuning.UPSTREAM_BACKOFF_MAX_MS)
        nextAttemptAtMs.set(System.currentTimeMillis() + backoff)
    }

    private fun onAttemptSucceeded() {
        attemptStreak = 0
        nextAttemptAtMs.set(0)
    }

    /** 建链前还需等待多久：取"本会话退避"与"全局建链时隙"的较大者。 */
    private fun waitBeforeAttemptMs(): Long {
        if (upstreamRelay != null) return 0L
        val perSession = nextAttemptAtMs.get() - System.currentTimeMillis()
        val sharedSlot = reserveConnectSlot()
        return maxOf(0L, perSession, sharedSlot).coerceAtMost(UdpTuning.UPSTREAM_BACKOFF_MAX_MS)
    }

    /**
     * 预约一个全局建链时隙，返回还需等待的毫秒数。
     *
     * 目的只是把多个会话的 connect 错开（上游不可用时不至于 N 个会话同时重连），
     * 不持有锁做实际连接——否则一个新会话的建链会被别人的连接超时拖住。
     */
    private fun reserveConnectSlot(): Long = synchronized(deps.connectGate) {
        val now = System.currentTimeMillis()
        val earliest = maxOf(deps.nextConnectAtMs.get(), now)
        deps.nextConnectAtMs.set(earliest + UdpTuning.UPSTREAM_CONNECT_SPACING_MS)
        earliest - now
    }

    /** 拆除上游 ASSOCIATE 会话；仅在失败/失效路径调用，成功路径不复位。 */
    private fun resetUpstream() {
        synchronized(gate) {
            runCatching { upControl?.close() }
            runCatching { upRelay?.close() }
            synchronized(deps.relays) { upRelay?.let { deps.relays.remove(it) } }
            upControl = null
            upRelay = null
            upstreamRelay = null
        }
    }

    /**
     * 监视上游控制连接：远端关闭（EOF）或读异常即拆链重建。
     * 不进冷却——上游重启不是建链失败，会话应立即自愈而非惩罚 5s。
     * close()/resetUpstream 已本地关 ctrl 时，read 抛异常但守卫不触发。
     */
    private fun watchUpstreamControl(ctrl: Socket, ctrlIn: DataInputStream) {
        try {
            // 控制连接在 ASSOCIATE 后不应再有数据；个别服务器发 keepalive 时忽略，
            // 持续读到真 EOF（远端关闭）或本地关闭导致的异常。
            while (ctrlIn.read() >= 0) { /* keepalive 字节，忽略 */ }
        } catch (_: Exception) {
        }
        if (deps.running.get() && !ctrl.isClosed && upControl === ctrl) {
            Log.i(TAG, "udp upstream control closed by peer, rebuilding association on demand")
            resetUpstream()
        }
    }

    private fun ensureUpstream(): Boolean {
        upstreamRelay?.let { return true }
        synchronized(gate) { upstreamRelay?.let { return true } }

        // 阻塞建链与 SOCKS 握手在 gate 外进行（秒级超时）：close()/resetUpstream()
        // 不必等待网络，写线程卡死也不影响 gate 的其它使用者。
        var ctrl: Socket? = null
        var relay: DatagramSocket? = null
        var ctrlIn: DataInputStream? = null
        try {
            ctrl = deps.openUpstream()
            // 远端 SOCKS5 的 BND 不在回环上时不能绑 127.0.0.1（公网回包会被内核丢弃）
            relay = if (deps.isLoopback(deps.upstream.host)) {
                DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            } else {
                DatagramSocket()
            }
            relay.receiveBufferSize = UdpTuning.SOCKET_BUFFER
            val ctrlOut = ctrl.getOutputStream()
            ctrlIn = DataInputStream(ctrl.getInputStream())
            ctrlOut.write(
                Socks5Codec.buildRequest(
                    Socks5Codec.CMD_UDP_ASSOCIATE,
                    Socks5Codec.ATYP_IPV4.toInt(),
                    "0.0.0.0",
                    0,
                ),
            )
            ctrlOut.flush()
            val bound = Socks5Codec.readReply(ctrlIn)
                ?: throw java.io.IOException("upstream UDP ASSOCIATE rejected")
            val address = deps.upstreamRelayAddress(bound.first, bound.second)

            // 发布在 gate 内做并复查：握手中途可能已被 close()/resetUpstream() 拆除，
            // 晚完成的握手只能关掉自己这份局部 socket，绝不覆盖已发布/已清空的状态。
            val ready = synchronized(gate) {
                when {
                    upstreamRelay != null -> false // 理论上的并发建链：让已发布者生效
                    !deps.running.get() || control.isClosed ->
                        throw java.io.IOException("associate closed during handshake")
                    else -> {
                        ctrl.soTimeout = 0 // 看门狗 read 无限阻塞（EOF/异常才是死亡信号）
                        upControl = ctrl
                        upRelay = relay
                        upstreamRelay = address
                        synchronized(deps.relays) { deps.relays.add(relay) }
                        true
                    }
                }
            }
            if (!ready) {
                runCatching { ctrl.close() }
                runCatching { relay.close() }
                return true
            }
            val c = ctrl
            val r = relay
            val ci = ctrlIn
            deps.pool.execute { receiveUpstreamLoop(r, c) }
            // 看门狗：远端关控制连接（上游重启/网络切换）即拆链，下一包懒重建。
            // UDP relay 本地 send 永远"成功"，控制连接 EOF 是上游静默死亡的唯一信号。
            deps.pool.execute { watchUpstreamControl(c, ci) }
            Log.i(
                TAG,
                "udp upstream relay 127.0.0.1:${r.localPort} -> " +
                    "${address.hostString}:${address.port}",
            )
            return true
        } catch (e: Exception) {
            Log.w(TAG, "udp upstream associate failed: ${e.message}")
            runCatching { ctrl?.close() }
            runCatching { relay?.close() }
            synchronized(gate) {
                // 本路径从未发布成功；仅当字段恰好指向自己（防御性）时清空，不碰别人的状态
                if (upControl === ctrl) {
                    upControl = null
                    upRelay = null
                    upstreamRelay = null
                }
            }
            // 不再在这里设全局冷却：那会把本会话的一次失败变成所有会话的黑洞。
            // 长退避由调用方按每会话指数退避承担，全局只保留建链错开（见 reserveConnectSlot）。
            return false
        }
    }

    private fun receiveUpstreamLoop(relay: DatagramSocket, ctrl: Socket) {
        val buf = ByteArray(UdpTuning.BUFFER_SIZE)
        while (deps.running.get() && !ctrl.isClosed && !relay.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                relay.soTimeout = UdpTuning.TIMEOUT_MS
                relay.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: java.net.PortUnreachableException) {
                // ICMP port-unreachable：只针对刚刚中继的那个目标，socket 对其它流仍然可用，
                // 绝不能拆链（否则一个关端口的目标会连累整个 ASSOCIATE 重建）。
                continue
            } catch (e: Exception) {
                // 非本地关闭的异常：中继已死但引用还在，写线程会一直 send "成功"却无回包；
                // 拆链让下一报文懒重建（与 watchUpstreamControl 对称）。
                if (deps.running.get() && !ctrl.isClosed && upRelay === relay) {
                    Log.w(TAG, "udp upstream relay died, rebuilding: ${e.message}")
                    resetUpstream()
                } else if (deps.running.get()) {
                    Log.v(TAG, "udp from-upstream: ${e.message}")
                }
                break
            }
            val target = peer.get() ?: continue
            // 上游回复本身已带 SOCKS UDP 头；防御性校验后透传给 hev
            if (packet.length < 4 ||
                packet.data[0] != 0.toByte() || packet.data[1] != 0.toByte()
            ) {
                continue
            }
            warnIfOversized(packet.length, "to-hev")
            try {
                clientRelay.send(DatagramPacket(packet.data.copyOf(packet.length), packet.length, target))
            } catch (_: Exception) {
                break
            }
        }
    }

    /**
     * 直连：域名走本地 DNS 解析（命中域名直连规则），IP 字面量直接用。
     * 返回是否已交给本地直连路径；本地解析/socket/发送失败时惩罚该目标并返回 false，
     * 调用方随即回退上游（UDP 无连接，远端不可达无法在此感知，不惩罚）。
     */
    private fun sendDirect(
        endpoint: Socks5Codec.UdpEndpoint,
        raw: ByteArray,
        payloadOffset: Int,
        payloadLen: Int,
    ): Boolean {
        if (payloadLen <= 0) return false
        val key = deps.udpDirectKey(endpoint.host, endpoint.port)
        val address: InetAddress? = when (endpoint.atyp) {
            Socks5Codec.ATYP_DOMAIN.toInt() -> deps.resolveHost(endpoint.host).firstOrNull()
            else -> runCatching { InetAddress.getByName(endpoint.host) }.getOrNull()
        }
        if (address == null) {
            deps.penalizeDirectKey(key)
            Log.i(TAG, "udp direct ${endpoint.host}:${endpoint.port} unresolved, penalized, via upstream")
            return false
        }
        val socket = obtainDirectSocket(address is Inet6Address)
        if (socket == null) {
            deps.penalizeDirectKey(key)
            return false
        }
        try {
            val payload = ByteArray(payloadLen)
            System.arraycopy(raw, payloadOffset, payload, 0, payloadLen)
            socket.send(DatagramPacket(payload, payloadLen, InetSocketAddress(address, endpoint.port)))
            return true
        } catch (e: Exception) {
            deps.penalizeDirectKey(key)
            Log.i(TAG, "udp direct ${endpoint.host}:${endpoint.port} send failed, penalized, via upstream: ${e.message}")
            return false
        }
    }

    private fun obtainDirectSocket(useV6: Boolean): DatagramSocket? = synchronized(gate) {
        val existing = if (useV6) directV6 else directV4
        if (existing != null) return existing
        val socket = runCatching {
            DatagramSocket().apply { receiveBufferSize = UdpTuning.SOCKET_BUFFER }
        }.getOrNull() ?: return null
        if (useV6) directV6 = socket else directV4 = socket
        synchronized(deps.relays) { deps.relays.add(socket) }
        deps.pool.execute { receiveDirectLoop(socket, useV6) }
        socket
    }

    private fun receiveDirectLoop(socket: DatagramSocket, useV6: Boolean) {
        val buf = ByteArray(UdpTuning.BUFFER_SIZE)
        while (deps.running.get() && !control.isClosed && !socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.soTimeout = UdpTuning.TIMEOUT_MS
                socket.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: java.net.PortUnreachableException) {
                continue // 某直发目标的 ICMP port-unreachable，非致命，socket 仍可用于其它流
            } catch (e: Exception) {
                // 非本地关闭的异常：清掉字段并关 socket，下一次 sendDirect 的
                // obtainDirectSocket 会新建（否则直发仍"成功"但回包永不再来）。
                if (deps.running.get() && !control.isClosed && !socket.isClosed) {
                    Log.w(TAG, "udp direct relay died, recreating: ${e.message}")
                    synchronized(gate) {
                        if (useV6) {
                            if (directV6 === socket) directV6 = null
                        } else {
                            if (directV4 === socket) directV4 = null
                        }
                    }
                    runCatching { socket.close() }
                    synchronized(deps.relays) { deps.relays.remove(socket) }
                } else if (deps.running.get()) {
                    Log.v(TAG, "udp direct recv: ${e.message}")
                }
                break
            }
            val target = peer.get() ?: continue
            val src = packet.socketAddress as? InetSocketAddress ?: continue
            val host = src.address?.hostAddress?.substringBefore('%') ?: continue
            // 这条才是 hev 用 1500 字节缓冲接收的方向，超限会被截成坏包。
            warnIfOversized(packet.length, "to-hev")
            val atyp = if (useV6) Socks5Codec.ATYP_IPV6.toInt()
            else Socks5Codec.ATYP_IPV4.toInt()
            val header = Socks5Codec.buildUdpHeader(atyp, host, src.port)
            val out = ByteArray(header.size + packet.length)
            System.arraycopy(header, 0, out, 0, header.size)
            System.arraycopy(packet.data, 0, out, header.size, packet.length)
            try {
                clientRelay.send(DatagramPacket(out, out.size, target))
            } catch (_: Exception) {
                break
            }
        }
    }

    /** 回程超 [UdpTuning.HEV_RECV_LIMIT] 的累计次数（QUIC 会持续触发，见下）。 */
    private val oversizeCount = AtomicInteger(0)

    /**
     * hev 回程缓冲是 1500（含 SOCKS 头），超限会被截成坏包——QUIC 首当其冲。
     *
     * QUIC 会源源不断地触发这条告警，一次性开关（只打第一次）会让"之后还在不在
     * 发生、有多频繁"永远不可见，排查时等于没有线索。改成首次 + 每 N 次采样，
     * 并带上累计次数，既能看到量级又不刷屏。
     */
    private fun warnIfOversized(length: Int, dir: String) {
        if (length <= UdpTuning.HEV_RECV_LIMIT) return
        val n = oversizeCount.incrementAndGet()
        if (n == 1 || n % UdpTuning.OVERSIZE_LOG_EVERY == 0) {
            Log.w(
                TAG,
                "udp $dir datagram ${length}B > ${UdpTuning.HEV_RECV_LIMIT}; " +
                    "hev will truncate QUIC (count=$n)",
            )
        }
    }
}

/**
 * [UdpSplitter] 的外部依赖：由 RuleSocksServer 注入，避免内层类隐式访问外层状态。
 *
 * [relays] 由调用方（RuleSocksServer）的同一把锁保护：本类所有对它的访问都写作
 * `synchronized(deps.relays)`，与调用方保持一致。
 */
internal class UdpDeps(
    val upstream: SocksUpstream,
    val running: AtomicBoolean,
    val relays: MutableList<DatagramSocket>,
    val pool: ExecutorService,
    /**
     * 全局"最早可发起上游建链"的时刻：只用于把多个会话的 connect 错开，
     * **不是**丢弃窗口——报文只会被延迟，不会因为别的会话失败而作废。
     */
    val nextConnectAtMs: AtomicLong,
    /** 保护 [nextConnectAtMs] 预约的监视器。 */
    val connectGate: Any,
    val shouldDirect: (host: String, atyp: Int) -> Boolean,
    val isDirectPenalizedKey: (key: String) -> Boolean,
    val penalizeDirectKey: (key: String) -> Unit,
    val udpDirectKey: (host: String, port: Int) -> String,
    val upstreamRelayAddress: (host: String, port: Int) -> InetSocketAddress,
    val resolveHost: (host: String) -> List<InetAddress>,
    val openUpstream: () -> Socket,
    val isLoopback: (host: String) -> Boolean,
)
