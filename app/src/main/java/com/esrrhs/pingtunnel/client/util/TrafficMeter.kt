package com.esrrhs.pingtunnel.client.util

/**
 * 根据周期性采样的隧道累计字节数计算实时速率（字节/秒）。
 *
 * 纯 JVM 逻辑、时间戳由调用方注入，便于单测。
 * hev 在重连后计数可能归零（新计数器小于旧值），此时间差按 0 处理而非负数。
 */
class TrafficMeter {

    data class Rates(val txBytesPerSec: Long, val rxBytesPerSec: Long)

    private var lastTx = 0L
    private var lastRx = 0L
    private var lastTsMs = 0L

    /** 建立新基线（首次采样或重连后），速率从 0 重新累计。 */
    fun rebaseline(tx: Long, rx: Long, nowMs: Long) {
        lastTx = tx
        lastRx = rx
        lastTsMs = nowMs
    }

    fun reset() {
        lastTx = 0L
        lastRx = 0L
        lastTsMs = 0L
    }

    /**
     * 输入本次采样的累计字节与时间戳，返回区间平均速率。
     * 首次采样（或 reset 后）只建基线，返回 0。
     */
    fun update(tx: Long, rx: Long, nowMs: Long): Rates {
        if (lastTsMs == 0L) {
            rebaseline(tx, rx, nowMs)
            return Rates(0L, 0L)
        }
        val dtMs = nowMs - lastTsMs
        if (dtMs <= 0L) return Rates(0L, 0L)
        val dTx = (tx - lastTx).coerceAtLeast(0L)
        val dRx = (rx - lastRx).coerceAtLeast(0L)
        lastTx = tx
        lastRx = rx
        lastTsMs = nowMs
        return Rates(dTx * 1000L / dtMs, dRx * 1000L / dtMs)
    }
}
