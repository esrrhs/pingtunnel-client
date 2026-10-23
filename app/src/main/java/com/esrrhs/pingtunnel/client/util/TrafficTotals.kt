package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.pingtunnel.Profile

/** 跨配置的流量汇总（纯逻辑，便于单测）。 */
data class TrafficTotal(val txBytes: Long, val rxBytes: Long) {
    val totalBytes: Long get() = txBytes + rxBytes
}

object TrafficTotals {

    fun of(profiles: List<Profile>): TrafficTotal =
        TrafficTotal(
            txBytes = profiles.sumOf { it.txBytes },
            rxBytes = profiles.sumOf { it.rxBytes },
        )
}
