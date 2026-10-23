package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalNetworkTrackerTest {

    @Test
    fun firstValidatedNetwork_isBaseline_notRebuild() {
        val tracker = PhysicalNetworkTracker()
        assertEquals(UpstreamAction.BASELINE, tracker.upsert(wifi("w", validated = true)))
        assertEquals("w", tracker.currentKey)
    }

    @Test
    fun sameNetworkUpdate_doesNothing() {
        val tracker = PhysicalNetworkTracker()
        tracker.upsert(wifi("w", validated = true, kbps = 10))
        assertEquals(
            UpstreamAction.NONE,
            tracker.upsert(wifi("w", validated = true, kbps = 50)),
        )
        assertEquals("w", tracker.currentKey)
    }

    @Test
    fun cellularBesideWifi_doesNotStealWifi() {
        val tracker = PhysicalNetworkTracker()
        tracker.upsert(wifi("w", validated = true, kbps = 1))
        assertEquals(
            UpstreamAction.NONE,
            tracker.upsert(cell("c", validated = true, kbps = 100_000)),
        )
        assertEquals("w", tracker.currentKey)
    }

    @Test
    fun wifiLost_switchesToCellular() {
        val tracker = PhysicalNetworkTracker()
        tracker.upsert(wifi("w", validated = true))
        tracker.upsert(cell("c", validated = true))
        assertEquals(UpstreamAction.SWITCH, tracker.remove("w"))
        assertEquals("c", tracker.currentKey)
    }

    @Test
    fun lastNetworkLost_clearsUntilNextArrives() {
        val tracker = PhysicalNetworkTracker()
        tracker.upsert(wifi("w", validated = true))
        assertEquals(UpstreamAction.CLEAR, tracker.remove("w"))
        assertEquals(null, tracker.currentKey)
        assertEquals(UpstreamAction.SWITCH, tracker.upsert(cell("c", validated = true)))
        assertEquals("c", tracker.currentKey)
    }

    @Test
    fun unvalidatedNetwork_isNotChosen() {
        val tracker = PhysicalNetworkTracker()
        assertEquals(UpstreamAction.NONE, tracker.upsert(wifi("w", validated = false)))
        assertEquals(null, tracker.currentKey)
        assertEquals(UpstreamAction.BASELINE, tracker.upsert(cell("c", validated = true)))
        assertEquals("c", tracker.currentKey)
    }

    @Test
    fun wifiLosesValidation_fallsBackToCellular() {
        val tracker = PhysicalNetworkTracker()
        tracker.upsert(wifi("w", validated = true))
        tracker.upsert(cell("c", validated = true))
        assertEquals(UpstreamAction.SWITCH, tracker.upsert(wifi("w", validated = false)))
        assertEquals("c", tracker.currentKey)
    }

    @Test
    fun seedWifiAndCellTogether_baselinesOnWifi() {
        val tracker = PhysicalNetworkTracker()
        assertEquals(
            UpstreamAction.BASELINE,
            tracker.seed(listOf(cell("c", validated = true, kbps = 100_000), wifi("w", validated = true))),
        )
        assertEquals("w", tracker.currentKey)
    }

    @Test
    fun reset_treatsNextNetworkAsBaselineAgain() {
        val tracker = PhysicalNetworkTracker()
        tracker.upsert(wifi("w", validated = true))
        tracker.remove("w")
        tracker.reset()
        assertEquals(UpstreamAction.BASELINE, tracker.upsert(cell("c", validated = true)))
        assertEquals("c", tracker.currentKey)
    }

    private fun wifi(key: String, validated: Boolean, kbps: Int = 10) =
        PhysicalNetworkTracker.Candidate(key, validated, preferTransport = true, kbps)

    private fun cell(key: String, validated: Boolean, kbps: Int = 5) =
        PhysicalNetworkTracker.Candidate(key, validated, preferTransport = false, kbps)
}
