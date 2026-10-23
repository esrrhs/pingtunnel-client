package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectQueueTest {

    @Test
    fun connectWhileDisconnecting_isQueuedAndRunsAfterTeardown() {
        val queue = VpnConnectQueue()
        assertEquals(ConnectCommand.QUEUE, queue.onConnect(VpnState.Disconnecting))
        assertTrue(queue.takeFollowUp(failed = false))
    }

    @Test
    fun disconnectAfterQueue_cancelsTheFollowUp() {
        val queue = VpnConnectQueue()
        queue.onConnect(VpnState.Disconnecting)
        assertFalse(queue.onDisconnect(VpnState.Disconnecting))
        assertFalse(queue.takeFollowUp(failed = false))
    }

    @Test
    fun clear_dropsQueuedConnect() {
        val queue = VpnConnectQueue()
        queue.onConnect(VpnState.Disconnecting)
        queue.clear()
        assertFalse(queue.takeFollowUp(failed = false))
    }

    @Test
    fun failedTeardown_dropsQueuedConnect() {
        val queue = VpnConnectQueue()
        queue.onConnect(VpnState.Disconnecting)
        assertFalse(queue.takeFollowUp(failed = true))
    }

    @Test
    fun connectWhileIdle_startsImmediately() {
        val queue = VpnConnectQueue()
        assertEquals(ConnectCommand.START, queue.onConnect(VpnState.Disconnected))
        assertEquals(ConnectCommand.START, queue.onConnect(VpnState.Paused))
        assertEquals(ConnectCommand.START, queue.onConnect(VpnState.Error("x")))
        assertFalse(queue.takeFollowUp(failed = false))
    }

    @Test
    fun connectWhileActive_isIgnored() {
        val queue = VpnConnectQueue()
        assertEquals(ConnectCommand.IGNORE, queue.onConnect(VpnState.Connecting))
        assertEquals(ConnectCommand.IGNORE, queue.onConnect(VpnState.Connected))
    }

    @Test
    fun disconnectWhileConnected_startsTeardown() {
        val queue = VpnConnectQueue()
        assertTrue(queue.onDisconnect(VpnState.Connected))
        assertTrue(queue.onDisconnect(VpnState.Connecting))
        assertTrue(queue.onDisconnect(VpnState.Paused))
        assertFalse(queue.onDisconnect(VpnState.Disconnected))
        assertFalse(queue.onDisconnect(VpnState.Disconnecting))
    }
}
