package com.esrrhs.pingtunnel.client.vpn

import kotlin.math.min
import kotlin.math.pow

/**
 * 断线/网络切换重连退避（纯逻辑，便于单测）。
 *
 * 1s、2s、4s，之后封顶 5s；重试无次数上限（由调用方在取消/成功时退出循环）。
 */
object ReconnectBackoff {

    const val BASE_MS = 1000L
    const val MAX_DELAY_MS = 5000L

    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        return min(BASE_MS * 2.0.pow(attempt - 1).toLong(), MAX_DELAY_MS)
    }
}
