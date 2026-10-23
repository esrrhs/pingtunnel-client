package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnTileStateTest {

    @Test
    fun activeSessionStates_renderActiveTile() {
        assertEquals(VpnTileState.Tile.ACTIVE, VpnTileState.of(VpnState.Connecting))
        assertEquals(VpnTileState.Tile.ACTIVE, VpnTileState.of(VpnState.Connected))
        assertEquals(VpnTileState.Tile.ACTIVE, VpnTileState.of(VpnState.Disconnecting))
    }

    @Test
    fun terminalStates_renderInactiveTile() {
        assertEquals(VpnTileState.Tile.INACTIVE, VpnTileState.of(VpnState.Disconnected))
        assertEquals(VpnTileState.Tile.INACTIVE, VpnTileState.of(VpnState.Paused))
        assertEquals(
            VpnTileState.Tile.INACTIVE,
            VpnTileState.of(VpnState.Error("boom")),
        )
    }
}
