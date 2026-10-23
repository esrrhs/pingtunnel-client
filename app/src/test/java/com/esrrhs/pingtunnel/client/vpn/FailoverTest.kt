package com.esrrhs.pingtunnel.client.vpn

import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailoverTest {

    private fun p(id: String, ping: Int = -1) = Profile(id = id, name = id, pingMs = ping)

    @Test
    fun noOtherProfile_returnsNull() {
        assertNull(Failover.pickNext(listOf(p("a")), "a"))
        assertNull(Failover.pickNext(emptyList(), "a"))
    }

    @Test
    fun prefersLowestMeasuredLatency() {
        val profiles = listOf(p("a", 100), p("b", 50), p("c", 500))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
    }

    @Test
    fun neverPicksCurrentProfile() {
        val profiles = listOf(p("a", 1), p("b", 2))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
        assertEquals("a", Failover.pickNext(profiles, "b")?.id)
    }

    @Test
    fun noneMeasured_fallsBackToFirstOther() {
        val profiles = listOf(p("a"), p("b"), p("c"))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
    }

    @Test
    fun measuredProfileWinsOverUnmeasured() {
        val profiles = listOf(p("a"), p("b", 300), p("c"))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
    }

    @Test
    fun orderedOthers_measuredFirstByLatencyThenOriginalOrder() {
        val profiles = listOf(
            p("a", 100),
            p("b"),
            p("c", 500),
            p("d", 200),
        )
        assertEquals(listOf("d", "c", "b"), Failover.orderedOthers(profiles, "a").map { it.id })
    }

    @Test
    fun orderedOthers_unmeasuredKeepListOrder() {
        val profiles = listOf(p("a"), p("b"), p("c"), p("d"))
        assertEquals(listOf("b", "c", "d"), Failover.orderedOthers(profiles, "a").map { it.id })
    }
}
