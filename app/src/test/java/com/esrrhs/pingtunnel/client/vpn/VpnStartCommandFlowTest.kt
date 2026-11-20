package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sequence tests for the control flow [PingtunnelVpnService] uses on CONNECT /
 * DISCONNECT. A recording [VpnForegroundAcker] proves promote-to-foreground
 * runs before queue / ignore / start / stop-idle work.
 *
 * These assertions fail on the pre-fix pattern that branched on QUEUE/IGNORE
 * without promoting first.
 */
class VpnStartCommandFlowTest {

    @Test
    fun connectWhileDisconnecting_promotesBeforeQueueSideEffect() {
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        val plan = VpnStartCommandFlow.planConnect(queue, VpnState.Disconnecting)
        assertEquals(ConnectCommand.QUEUE, plan.command)

        VpnStartCommandFlow.executeConnect(
            plan = plan,
            acker = { events += "ack:${it.name}" },
            onStart = { events += "start" },
            onQueued = { events += "queued" },
            onIgnored = { events += "ignored" },
        )

        assertEquals(listOf("ack:Disconnecting", "queued"), events)
        assertTrue(queue.takeFollowUp(failed = false))
    }

    @Test
    fun connectStart_promotesBeforeLaunchingConnectWork() {
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        val plan = VpnStartCommandFlow.planConnect(queue, VpnState.Disconnected)

        VpnStartCommandFlow.executeConnect(
            plan = plan,
            acker = { events += "ack:${it.name}" },
            onStart = { events += "start" },
            onQueued = { events += "queued" },
        )

        assertEquals(listOf("ack:Connecting", "start"), events)
    }

    @Test
    fun connectIgnoredWhileConnected_promotesBeforeIgnore() {
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        val plan = VpnStartCommandFlow.planConnect(queue, VpnState.Connected)

        VpnStartCommandFlow.executeConnect(
            plan = plan,
            acker = { events += "ack:${it.name}" },
            onStart = { events += "start" },
            onIgnored = { events += "ignored" },
        )

        assertEquals(listOf("ack:Connected", "ignored"), events)
    }

    @Test
    fun legacyQueueWithoutPromote_missesRequiredAck() {
        // Pre-fix Service behavior (must remain a failing sequence).
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        val command = queue.onConnect(VpnState.Disconnecting)
        assertEquals(ConnectCommand.QUEUE, command)
        // Old code: log / return without promote.
        events += "queued"
        assertFalse(
            "legacy QUEUE path must not record an ack",
            events.any { it.startsWith("ack:") },
        )
    }

    @Test
    fun disconnectWhileConnected_promotesThenStartsTeardown() {
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        val plan = VpnStartCommandFlow.planDisconnect(queue, VpnState.Connected)
        assertEquals(DisconnectDisposition.StartTeardown, plan.disposition)

        VpnStartCommandFlow.executeDisconnect(
            plan = plan,
            acker = { events += "ack:${it.name}" },
            onStartTeardown = { events += "teardown" },
            onStopIdle = { events += "stop-idle" },
        )

        assertEquals(listOf("ack:Disconnecting", "teardown"), events)
    }

    @Test
    fun disconnectWhileAlreadyDisconnecting_promotesOnly() {
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        queue.onConnect(VpnState.Disconnecting) // queued follow-up
        val plan = VpnStartCommandFlow.planDisconnect(queue, VpnState.Disconnecting)
        assertEquals(DisconnectDisposition.KeepTeardownRunning, plan.disposition)
        // onDisconnect clears the queued follow-up (existing queue semantics).
        assertFalse(queue.takeFollowUp(failed = false))

        VpnStartCommandFlow.executeDisconnect(
            plan = plan,
            acker = { events += "ack:${it.name}" },
            onStartTeardown = { events += "teardown" },
            onStopIdle = { events += "stop-idle" },
        )

        assertEquals(listOf("ack:Disconnecting"), events)
    }

    @Test
    fun disconnectWhileIdle_promotesThenStopsService() {
        val events = mutableListOf<String>()
        val queue = VpnConnectQueue()
        val plan = VpnStartCommandFlow.planDisconnect(queue, VpnState.Disconnected)
        assertEquals(DisconnectDisposition.StopIdleService, plan.disposition)

        VpnStartCommandFlow.executeDisconnect(
            plan = plan,
            acker = { events += "ack:${it.name}" },
            onStartTeardown = { events += "teardown" },
            onStopIdle = { events += "stop-idle" },
        )

        assertEquals(listOf("ack:Disconnecting", "stop-idle"), events)
    }

    @Test
    fun executeConnect_requiresAckerInvocationEvenIfCallbacksNoop() {
        var promoted = false
        val plan = ConnectPlan(ConnectCommand.QUEUE, VpnForegroundNotice.Disconnecting)
        VpnStartCommandFlow.executeConnect(
            plan = plan,
            acker = { promoted = true },
            onStart = {},
            onQueued = {},
        )
        assertTrue(promoted)
    }
}
