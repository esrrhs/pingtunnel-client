package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the stopForeground/stopSelf ordering hazard: a stale teardown must not
 * call removeForeground after a newer CONNECT has already promoted.
 */
class VpnServiceCleanupTest {

    @Test
    fun currentIds_removeForegroundThenStopSelf() {
        val events = mutableListOf<String>()
        val applied = VpnServiceCleanup.applyIfCurrent(
            expectedStartId = 3,
            latestStartId = 3,
            removeForeground = { events += "removeFg" },
            stopSelf = { events += "stop:$it" },
        )
        assertTrue(applied)
        assertEquals(listOf("removeFg", "stop:3"), events)
    }

    @Test
    fun staleIds_skipRemoveAndStopEntirely() {
        val events = mutableListOf<String>()
        val applied = VpnServiceCleanup.applyIfCurrent(
            expectedStartId = 5,
            latestStartId = 6,
            removeForeground = { events += "removeFg" },
            stopSelf = { events += "stop:$it" },
        )
        assertFalse(applied)
        assertTrue(events.isEmpty())
    }

    @Test
    fun teardownDecidedStop_thenNewConnectPromotes_thenOldCleanupSkipsRemove() {
        // Mirrors Service: capture expected under lock, later promote bumps latest,
        // then cleanup re-checks before stopForeground.
        var latestStartId = 5
        val events = mutableListOf<String>()
        val expectedFromTeardown = latestStartId

        // Newer onStartCommand CONNECT
        latestStartId = 6
        events += "promote:$latestStartId"

        val applied = VpnServiceCleanup.applyIfCurrent(
            expectedStartId = expectedFromTeardown,
            latestStartId = latestStartId,
            removeForeground = { events += "removeFg" },
            stopSelf = { events += "stop:$it" },
        )

        assertFalse(applied)
        assertEquals(listOf("promote:6"), events)
    }

    @Test
    fun noNewerStart_cleanupRemovesAndStops() {
        var latestStartId = 9
        val expected = latestStartId
        val events = mutableListOf<String>()
        assertTrue(
            VpnServiceCleanup.applyIfCurrent(
                expected,
                latestStartId,
                removeForeground = { events += "removeFg" },
                stopSelf = { events += "stop:$it" },
            ),
        )
        assertEquals(listOf("removeFg", "stop:9"), events)
    }

    @Test
    fun queueReconnectStillFollowsWhenCleanupSkipped() {
        val queue = VpnConnectQueue()
        assertEquals(ConnectCommand.QUEUE, queue.onConnect(VpnState.Disconnecting))
        // Teardown would takeFollowUp instead of finishing — preserved semantics.
        assertTrue(queue.takeFollowUp(failed = false))
        // If it had finished with a stale id after a newer promote, cleanup skips:
        assertFalse(VpnServiceCleanup.isCurrent(expectedStartId = 1, latestStartId = 2))
    }
}
