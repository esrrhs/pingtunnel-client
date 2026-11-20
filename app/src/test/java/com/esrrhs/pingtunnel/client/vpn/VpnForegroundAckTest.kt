package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/** Notice mapping only; call-order coverage lives in [VpnStartCommandFlowTest]. */
class VpnForegroundAckTest {

    @Test
    fun connectStart_mapsToConnecting() {
        assertEquals(
            VpnForegroundNotice.Connecting,
            VpnForegroundAck.afterConnect(ConnectCommand.START, VpnState.Disconnected),
        )
    }

    @Test
    fun connectQueued_mapsToDisconnecting() {
        assertEquals(
            VpnForegroundNotice.Disconnecting,
            VpnForegroundAck.afterConnect(ConnectCommand.QUEUE, VpnState.Disconnecting),
        )
    }

    @Test
    fun connectIgnoredWhileConnected_mapsToConnected() {
        assertEquals(
            VpnForegroundNotice.Connected,
            VpnForegroundAck.afterConnect(ConnectCommand.IGNORE, VpnState.Connected),
        )
    }

    @Test
    fun connectIgnoredWhileConnecting_mapsToConnecting() {
        assertEquals(
            VpnForegroundNotice.Connecting,
            VpnForegroundAck.afterConnect(ConnectCommand.IGNORE, VpnState.Connecting),
        )
    }
}
