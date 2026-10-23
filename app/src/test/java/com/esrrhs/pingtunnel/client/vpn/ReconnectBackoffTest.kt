package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun delaysDoubleThenCapAtFiveSeconds_withoutAttemptLimit() {
        assertEquals(1000L, ReconnectBackoff.delayMs(1))
        assertEquals(2000L, ReconnectBackoff.delayMs(2))
        assertEquals(4000L, ReconnectBackoff.delayMs(3))
        // 封顶 5s，之后一直是 5s（重试无次数上限）
        assertEquals(5000L, ReconnectBackoff.delayMs(4))
        assertEquals(5000L, ReconnectBackoff.delayMs(5))
        assertEquals(5000L, ReconnectBackoff.delayMs(50))
    }

    @Test
    fun attemptZero_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectBackoff.delayMs(0)
        }
    }
}
