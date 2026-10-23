package com.esrrhs.pingtunnel.client.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** UI 层启停 VPN 的唯一入口；状态请读 [VpnStateHolder]。 */
object VpnController {

    fun connect(context: Context) {
        val intent = Intent(context, PingtunnelVpnService::class.java)
            .setAction(PingtunnelVpnService.ACTION_CONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, PingtunnelVpnService::class.java)
            .setAction(PingtunnelVpnService.ACTION_DISCONNECT)
        ContextCompat.startForegroundService(context, intent)
    }
}
