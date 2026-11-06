package com.esrrhs.pingtunnel.client

import android.app.Application
import com.esrrhs.pingtunnel.client.data.SettingsRepository
import com.esrrhs.pingtunnel.client.util.AppLanguage
import com.esrrhs.pingtunnel.client.util.DirectProxySelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程入口。
 */
class PingtunnelApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        DirectProxySelector.install()

        // 启动时初始化语言配置
        appScope.launch {
            val settings = SettingsRepository(this@PingtunnelApp).current()
            if (settings.appLanguage.isNotEmpty()) {
                AppLanguage.applyLanguage(this@PingtunnelApp, settings.appLanguage)
            }
        }
    }
}
