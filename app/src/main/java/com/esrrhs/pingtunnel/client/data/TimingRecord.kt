package com.esrrhs.pingtunnel.client.data

import kotlinx.serialization.Serializable

/**
 * 单条网络请求的时序明细（兼容 Navigation Timing 与 Resource Timing API）。
 * 时间单位统一为毫秒（ms）。
 */
@Serializable
data class TimingRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val initiatorType: String = "",
    val startTime: Double = 0.0,
    val responseEnd: Double = 0.0,
    val duration: Double = 0.0,
    // 阶段细分
    val unloadStart: Double = 0.0,
    val unloadEnd: Double = 0.0,
    val redirectStart: Double = 0.0,
    val redirectEnd: Double = 0.0,
    val fetchStart: Double = 0.0,
    val dnsStart: Double = 0.0,
    val dnsEnd: Double = 0.0,
    val connectStart: Double = 0.0,
    val connectEnd: Double = 0.0,
    val sslStart: Double = 0.0,
    val sslEnd: Double = 0.0,
    val requestStart: Double = 0.0,
    val responseStart: Double = 0.0,
    val transferSize: Long = 0L,
    val decodedBodySize: Long = 0L,
    val nextHopProtocol: String = "",
    val pageUrl: String = "",
    val timeOrigin: Double = 0.0, // 页面全局时间起点 window.performance.timeOrigin (毫秒 Unix 时间戳)
    val userTriggerMs: Long = 0L, // 用户点击回车/导航触发的物理时间戳 (ms Unix epoch)
) {
    /** 绝对起始绝对时间戳 (ms Unix epoch) */
    val absoluteStartMs: Long
        get() = if (timeOrigin > 0.0) (timeOrigin + startTime).toLong() else 0L

    /** 触发到导航启动耗时 (UI 事件/Binder 跨进程分发与引擎调度) */
    val dispatchDuration: Double
        get() = if (userTriggerMs > 0L && timeOrigin > 0.0 && timeOrigin >= userTriggerMs) {
            (timeOrigin - userTriggerMs.toDouble()).coerceAtLeast(0.0)
        } else 0.0

    /** 本地应用缓存/重定向/HSTS策略比对耗时 (从 fetchStart 到真正网络查找开始) */
    val cacheOrPrecheckDuration: Double
        get() = when {
            dnsStart > fetchStart && fetchStart > 0 -> dnsStart - fetchStart
            connectStart > fetchStart && fetchStart > 0 -> connectStart - fetchStart
            requestStart > fetchStart && fetchStart > 0 -> requestStart - fetchStart
            else -> 0.0
        }

    /** 网络排队/Socket 等待配额耗时 (Stalling / Queueing: 从开始请求到 connectStart 或从 connectEnd 到 requestStart) */
    val queueingDuration: Double
        get() = if (requestStart > connectEnd && connectEnd > 0) {
            (requestStart - connectEnd).coerceAtLeast(0.0)
        } else if (connectStart > dnsEnd && dnsEnd > 0) {
            (connectStart - dnsEnd).coerceAtLeast(0.0)
        } else 0.0

    /** DNS 查找耗时（ms） */
    val dnsDuration: Double
        get() = if (dnsEnd > dnsStart && dnsStart > 0) dnsEnd - dnsStart else 0.0

    /** TCP 握手耗时（含 SSL） */
    val connectDuration: Double
        get() = if (connectEnd > connectStart && connectStart > 0) connectEnd - connectStart else 0.0

    /** SSL/TLS 握手耗时 */
    val sslDuration: Double
        get() = if (sslEnd > sslStart && sslStart > 0) sslEnd - sslStart else 0.0

    /** TTFB (Time to First Byte): 从发出请求到收到第一个响应字节 */
    val ttfb: Double
        get() = if (responseStart > requestStart && requestStart > 0) responseStart - requestStart else 0.0

    /** 内容下载耗时 */
    val downloadDuration: Double
        get() = if (responseEnd > responseStart && responseStart > 0) responseEnd - responseStart else 0.0

    /** 是否有完整的网络建连阶段（缓存或复用连接的请求可能为 0） */
    val hasConnectionPhase: Boolean
        get() = dnsDuration > 0 || connectDuration > 0
}
