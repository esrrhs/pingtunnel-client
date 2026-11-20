package com.esrrhs.pingtunnel.client.vpn

/**
 * Decides whether a deferred service shutdown may still run.
 *
 * Teardown often captures a [expectedStartId] on a worker thread, then a newer
 * `onStartCommand` can promote the foreground for a reconnect. Calling
 * `stopForeground` before `stopSelf(oldId)` would strip that new notification
 * even when `stopSelf(oldId)` is a no-op — so both actions must be skipped when
 * [expectedStartId] is stale.
 *
 * [PingtunnelVpnService] must invoke [applyIfCurrent] while holding the same
 * lock that updates `latestStartId` and performs promote-to-foreground.
 */
internal object VpnServiceCleanup {
    fun isCurrent(expectedStartId: Int, latestStartId: Int): Boolean =
        expectedStartId == latestStartId

    /**
     * @return true if remove+stop ran; false if skipped as stale.
     */
    fun applyIfCurrent(
        expectedStartId: Int,
        latestStartId: Int,
        removeForeground: () -> Unit,
        stopSelf: (startId: Int) -> Unit,
    ): Boolean {
        if (!isCurrent(expectedStartId, latestStartId)) return false
        removeForeground()
        stopSelf(expectedStartId)
        return true
    }
}
