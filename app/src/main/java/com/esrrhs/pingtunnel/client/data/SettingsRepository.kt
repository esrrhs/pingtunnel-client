package com.esrrhs.pingtunnel.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "pingtunnel_settings")

/** 全局设置仓库。 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    private object Keys {
        val BOOT_START = booleanPreferencesKey("boot_start")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val FAILOVER = booleanPreferencesKey("failover")
        val DEFAULT_BYPASS_LAN = booleanPreferencesKey("default_bypass_lan")
        val TRUSTED_WIFI_ENABLED = booleanPreferencesKey("trusted_wifi_enabled")
        val TRUSTED_WIFI_SSIDS = stringSetPreferencesKey("trusted_wifi_ssids")
        val DOMAIN_DIRECT_ENABLED = booleanPreferencesKey("domain_direct_enabled")
        val DOMAIN_DIRECT_RULES = stringPreferencesKey("domain_direct_rules")

        /** 一次性迁移标记：把「智能连接」类开关的历史默认值刷为开启。 */
        val MIGRATED_SMART_DEFAULTS = booleanPreferencesKey("migrated_smart_defaults_v1")
    }

    private val migrated = flow {
        store.edit { prefs ->
            if (prefs[Keys.MIGRATED_SMART_DEFAULTS] != true) {
                prefs[Keys.BOOT_START] = true
                prefs[Keys.AUTO_RECONNECT] = true
                prefs[Keys.FAILOVER] = true
                prefs[Keys.DEFAULT_BYPASS_LAN] = true
                prefs[Keys.DOMAIN_DIRECT_ENABLED] = true
                prefs[Keys.MIGRATED_SMART_DEFAULTS] = true
            }
        }
        emit(Unit)
    }

    val settings: Flow<AppSettings> = migrated.flatMapLatest {
        store.data.map { prefs ->
            AppSettings(
                bootStart = prefs[Keys.BOOT_START] ?: true,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                failover = prefs[Keys.FAILOVER] ?: true,
                defaultBypassLan = prefs[Keys.DEFAULT_BYPASS_LAN] ?: true,
                trustedWifiEnabled = prefs[Keys.TRUSTED_WIFI_ENABLED] ?: false,
                trustedWifiSsids = prefs[Keys.TRUSTED_WIFI_SSIDS] ?: emptySet(),
                domainDirectEnabled = prefs[Keys.DOMAIN_DIRECT_ENABLED] ?: true,
                domainDirectRulesText = prefs[Keys.DOMAIN_DIRECT_RULES] ?: "",
            )
        }
    }

    suspend fun save(settings: AppSettings) {
        store.edit { prefs ->
            prefs[Keys.BOOT_START] = settings.bootStart
            prefs[Keys.AUTO_RECONNECT] = settings.autoReconnect
            prefs[Keys.FAILOVER] = settings.failover
            prefs[Keys.DEFAULT_BYPASS_LAN] = settings.defaultBypassLan
            prefs[Keys.TRUSTED_WIFI_ENABLED] = settings.trustedWifiEnabled
            prefs[Keys.TRUSTED_WIFI_SSIDS] = settings.trustedWifiSsids
            prefs[Keys.DOMAIN_DIRECT_ENABLED] = settings.domainDirectEnabled
            prefs[Keys.DOMAIN_DIRECT_RULES] = settings.domainDirectRulesText
        }
    }

    /** 供一次性读取使用。 */
    suspend fun current(): AppSettings = settings.first()
}
