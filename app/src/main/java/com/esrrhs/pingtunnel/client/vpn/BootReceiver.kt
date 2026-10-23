package com.esrrhs.pingtunnel.client.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.esrrhs.pingtunnel.client.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 开机完成后按全局设置自动连接上次配置。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).settings.first()
                if (settings.bootStart) {
                    VpnController.connect(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
