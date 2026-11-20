package com.esrrhs.pingtunnel.client.vpn

/** Notification copy used when promoting the VPN service to the foreground. */
internal enum class VpnForegroundNotice {
    Connecting,
    Disconnecting,
    Connected,
}

/**
 * Maps queue decisions to notification copy for [VpnStartCommandFlow].
 *
 * Kept separate so notice choice stays obvious in tests; the Service must still
 * go through [VpnStartCommandFlow.executeConnect] / [executeDisconnect] so the
 * promote-to-foreground callback runs before any queued/ignored/start work.
 */
internal object VpnForegroundAck {
    fun afterConnect(
        command: ConnectCommand,
        stateWhenDecided: VpnState,
    ): VpnForegroundNotice = when (command) {
        ConnectCommand.START -> VpnForegroundNotice.Connecting
        // Teardown still owns the session; keep disconnecting copy until follow-up.
        ConnectCommand.QUEUE -> VpnForegroundNotice.Disconnecting
        ConnectCommand.IGNORE -> when (stateWhenDecided) {
            VpnState.Connected -> VpnForegroundNotice.Connected
            else -> VpnForegroundNotice.Connecting
        }
    }
}
