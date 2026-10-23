package com.esrrhs.pingtunnel.client.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.esrrhs.pingtunnel.client.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.esrrhs.pingtunnel.client.R

/**
 * 下拉通知栏的 Quick Settings 磁贴：一键启停 VPN。
 *
 * - 状态跟随 [VpnStateHolder]（Service 与本磁贴同进程，前台服务存活即进程存活）。
 * - 首次使用尚未授权 VPN 时，磁贴无法拿授权结果，收起面板并打开 App 走授权流程。
 */
class PingtunnelTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        render(VpnStateHolder.state.value)
        stateJob = scope.launch {
            VpnStateHolder.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
    }

    override fun onClick() {
        val toggle = {
            val state = VpnStateHolder.state.value
            when (VpnTileState.of(state)) {
                VpnTileState.Tile.ACTIVE ->
                    VpnController.disconnect(applicationContext)
                VpnTileState.Tile.INACTIVE -> startOrAskConsent()
            }
        }
        if (isLocked) {
            unlockAndRun(toggle)
        } else {
            toggle()
        }
    }

    /** 已授权则直接连接；否则打开 App 由用户确认系统 VPN 授权弹窗。 */
    private fun startOrAskConsent() {
        if (VpnService.prepare(this) == null) {
            VpnController.connect(applicationContext)
        } else {
            openAppForConsent()
        }
    }

    private fun openAppForConsent() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONNECT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        tile.state = when (VpnTileState.of(state)) {
            VpnTileState.Tile.ACTIVE -> Tile.STATE_ACTIVE
            VpnTileState.Tile.INACTIVE -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (state) {
            VpnState.Disconnected -> getString(R.string.tile_disconnected)
            VpnState.Connecting -> getString(R.string.tile_connecting)
            VpnState.Connected -> getString(R.string.tile_connected)
            VpnState.Disconnecting -> getString(R.string.tile_disconnecting)
            VpnState.Paused -> getString(R.string.tile_paused)
            is VpnState.Error -> getString(R.string.tile_disconnected)
        }
        tile.updateTile()
    }
}
