package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMeterTest {

    @Test
    fun firstSample_onlyEstablishesBaseline() {
        val meter = TrafficMeter()
        val rates = meter.update(tx = 1000L, rx = 2000L, nowMs = 1000L)
        assertEquals(0L, rates.txBytesPerSec)
        assertEquals(0L, rates.rxBytesPerSec)
    }

    @Test
    fun secondSample_computesBytesPerSecond() {
        val meter = TrafficMeter()
        meter.update(1000L, 2000L, 1000L)
        // 500ms 内新增 500/1000 字节 → 1000/2000 B/s
        val rates = meter.update(1500L, 3000L, 1500L)
        assertEquals(1000L, rates.txBytesPerSec)
        assertEquals(2000L, rates.rxBytesPerSec)
    }

    @Test
    fun counterRestartAfterReconnect_neverProducesNegativeRate() {
        val meter = TrafficMeter()
        meter.update(1_000_000L, 2_000_000L, 1000L)
        // hev 重连后计数器归零变小
        val rates = meter.update(100L, 200L, 2000L)
        assertEquals(0L, rates.txBytesPerSec)
        assertEquals(0L, rates.rxBytesPerSec)
        // 下一次采样按新计数器正常计算
        val next = meter.update(1100L, 2200L, 3000L)
        assertEquals(1000L, next.txBytesPerSec)
        assertEquals(2000L, next.rxBytesPerSec)
    }

    @Test
    fun nonAdvancingTimestamp_reportsZeroRate() {
        val meter = TrafficMeter()
        meter.update(0L, 0L, 1000L)
        val rates = meter.update(5000L, 9000L, 1000L)
        assertEquals(0L, rates.txBytesPerSec)
        assertEquals(0L, rates.rxBytesPerSec)
    }

    @Test
    fun reset_forgetsBaseline() {
        val meter = TrafficMeter()
        meter.update(1000L, 2000L, 1000L)
        meter.reset()
        val rates = meter.update(5000L, 9000L, 2000L)
        assertEquals(0L, rates.txBytesPerSec)
        assertEquals(0L, rates.rxBytesPerSec)
    }

    @Test
    fun rebaseline_restartsRateFromGivenPoint() {
        val meter = TrafficMeter()
        meter.update(0L, 0L, 0L)
        meter.update(9_999_999L, 9_999_999L, 500L)
        meter.rebaseline(tx = 100L, rx = 200L, nowMs = 1000L)
        val rates = meter.update(1100L, 2200L, 2000L)
        assertEquals(1000L, rates.txBytesPerSec)
        assertEquals(2000L, rates.rxBytesPerSec)
    }
}
