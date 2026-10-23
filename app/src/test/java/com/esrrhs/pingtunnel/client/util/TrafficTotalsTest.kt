package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficTotalsTest {

    @Test
    fun emptyProfiles_sumToZero() {
        val total = TrafficTotals.of(emptyList())
        assertEquals(0L, total.txBytes)
        assertEquals(0L, total.rxBytes)
        assertEquals(0L, total.totalBytes)
    }

    @Test
    fun sumsAcrossAllProfiles() {
        val profiles = listOf(
            Profile(id = "a", name = "a", txBytes = 100L, rxBytes = 200L),
            Profile(id = "b", name = "b", txBytes = 300L, rxBytes = 400L),
            Profile(id = "c", name = "c"),
        )
        val total = TrafficTotals.of(profiles)
        assertEquals(400L, total.txBytes)
        assertEquals(600L, total.rxBytes)
        assertEquals(1000L, total.totalBytes)
    }
}
