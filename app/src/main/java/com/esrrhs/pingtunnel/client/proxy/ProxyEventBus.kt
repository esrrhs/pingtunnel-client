package com.esrrhs.pingtunnel.client.proxy

import java.util.concurrent.ConcurrentLinkedQueue

/** 一次 TCP CONNECT 的判定与结果（由 [RuleSocksServer] 产生）。 */
data class ProxyConnectEvent(
    /** 请求目标：域名或 IP 字面量。 */
    val host: String,
    val port: Int,
    /** true=本地直连（命中域名规则）；false=转发 SPP 隧道。 */
    val direct: Boolean,
    val success: Boolean,
    /** 失败原因摘要（成功时为 null）。 */
    val reason: String?,
    val durationMs: Int,
    val ts: Long,
)

/**
 * 进程内短期事件缓冲：分流器在 TCP CONNECT 结束时写入，
 * 全局连接记录器据此把每条会话标注为「直连/代理」「成功/失败」及原因。
 * UDP 无 CONNECT 过程，不会有事件。
 */
object ProxyEventBus {

    private const val MAX_AGE_MS = 120_000L
    private const val MAX_EVENTS = 2000

    private val queue = ConcurrentLinkedQueue<ProxyConnectEvent>()

    fun record(
        host: String,
        port: Int,
        direct: Boolean,
        success: Boolean,
        reason: String?,
        durationMs: Int,
    ) {
        val now = System.currentTimeMillis()
        queue.add(
            ProxyConnectEvent(
                host = host,
                port = port,
                direct = direct,
                success = success,
                reason = reason,
                durationMs = durationMs,
                ts = now,
            ),
        )
        prune(now)
    }

    /**
     * 查找 [notBeforeMs] 之后目标匹配（host 相等、端口一致）的最新事件；
     * 优先返回失败事件（一个目标可能重试多次）。消费后不移除（同一目标多会话允许复用）。
     */
    fun find(host: String?, port: Int, notBeforeMs: Long): ProxyConnectEvent? {
        if (host.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        var bestFailure: ProxyConnectEvent? = null
        var bestSuccess: ProxyConnectEvent? = null
        val it = queue.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.ts > MAX_AGE_MS || e.ts < notBeforeMs || e.port != port) continue
            if (!hostEquals(e.host, host)) continue
            // 同目标可能重试多次：失败优先，其次取最新
            if (e.success) {
                if (bestSuccess == null || e.ts > bestSuccess.ts) bestSuccess = e
            } else {
                if (bestFailure == null || e.ts > bestFailure.ts) bestFailure = e
            }
        }
        return bestFailure ?: bestSuccess
    }

    private fun hostEquals(a: String, b: String): Boolean =
        a.equals(b, ignoreCase = true).let { eq ->
            if (eq) true else a.trimEnd('.').equals(b.trimEnd('.'), ignoreCase = true)
        }

    private fun prune(now: Long) {
        while (true) {
            val head = queue.peek() ?: break
            if (now - head.ts <= MAX_AGE_MS && queue.size <= MAX_EVENTS) break
            queue.poll()
        }
    }
}
