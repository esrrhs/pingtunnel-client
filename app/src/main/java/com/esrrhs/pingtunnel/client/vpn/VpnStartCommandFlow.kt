package com.esrrhs.pingtunnel.client.vpn

/**
 * Control flow for [PingtunnelVpnService.onStartCommand] CONNECT / DISCONNECT.
 *
 * Callers started with `Context.startForegroundService` must promote the service
 * to the foreground (via [VpnForegroundAcker]) before returning from
 * `onStartCommand`. Skipping that on QUEUE / IGNORE / no-op DISCONNECT caused
 * `ForegroundServiceDidNotStartInTimeException` when E2E reconnected during
 * teardown.
 *
 * [executeConnect] / [executeDisconnect] always invoke [VpnForegroundAcker.promote]
 * before side effects so regressions that omit the ack fail sequence tests.
 */
internal fun interface VpnForegroundAcker {
    fun promote(notice: VpnForegroundNotice)
}

internal enum class DisconnectDisposition {
    /** Transition to Disconnecting and run teardown. */
    StartTeardown,

    /** Teardown already running; only satisfy the foreground contract. */
    KeepTeardownRunning,

    /** Already idle; satisfy foreground contract then stop this startId. */
    StopIdleService,
}

internal data class ConnectPlan(
    val command: ConnectCommand,
    val notice: VpnForegroundNotice,
)

internal data class DisconnectPlan(
    val disposition: DisconnectDisposition,
    val notice: VpnForegroundNotice,
)

internal object VpnStartCommandFlow {

    fun planConnect(queue: VpnConnectQueue, state: VpnState): ConnectPlan {
        val command = queue.onConnect(state)
        return ConnectPlan(command, VpnForegroundAck.afterConnect(command, state))
    }

    fun planDisconnect(queue: VpnConnectQueue, state: VpnState): DisconnectPlan {
        val startTeardown = queue.onDisconnect(state)
        val disposition = when {
            startTeardown -> DisconnectDisposition.StartTeardown
            state is VpnState.Disconnecting -> DisconnectDisposition.KeepTeardownRunning
            else -> DisconnectDisposition.StopIdleService
        }
        return DisconnectPlan(disposition, VpnForegroundNotice.Disconnecting)
    }

    fun executeConnect(
        plan: ConnectPlan,
        acker: VpnForegroundAcker,
        onStart: () -> Unit = {},
        onQueued: () -> Unit = {},
        onIgnored: () -> Unit = {},
    ) {
        acker.promote(plan.notice)
        when (plan.command) {
            ConnectCommand.START -> onStart()
            ConnectCommand.QUEUE -> onQueued()
            ConnectCommand.IGNORE -> onIgnored()
        }
    }

    fun executeDisconnect(
        plan: DisconnectPlan,
        acker: VpnForegroundAcker,
        onStartTeardown: () -> Unit,
        onStopIdle: () -> Unit,
    ) {
        acker.promote(plan.notice)
        when (plan.disposition) {
            DisconnectDisposition.StartTeardown -> onStartTeardown()
            DisconnectDisposition.KeepTeardownRunning -> Unit
            DisconnectDisposition.StopIdleService -> onStopIdle()
        }
    }
}
