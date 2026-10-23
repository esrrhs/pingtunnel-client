package com.esrrhs.pingtunnel.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "pingtunnel_config")

/** 多配置持久化仓库：配置列表 + 当前选中项 + 每配置累计流量。 */
class ConfigRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    private object Keys {
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val ACTIVE_ID = stringPreferencesKey("active_id")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Profile.serializer())

    /** 全部配置。 */
    val profiles: Flow<List<Profile>> = dataStore.data.map { prefs ->
        val stored = prefs[Keys.PROFILES_JSON]
        if (stored != null) {
            runCatching { json.decodeFromString(listSerializer, stored) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    /** 当前生效配置 id；未显式选择时默认为第一条。 */
    val activeId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_ID]
    }

    suspend fun upsert(profile: Profile) {
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON]).toMutableList()
            val index = list.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                // 保留已累计的流量，编辑只改名称与参数
                list[index] = profile.copy(txBytes = list[index].txBytes, rxBytes = list[index].rxBytes)
            } else {
                list.add(profile)
            }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
            if (prefs[Keys.ACTIVE_ID] == null) {
                prefs[Keys.ACTIVE_ID] = profile.id
            }
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON]).filterNot { it.id == id }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
            if (prefs[Keys.ACTIVE_ID] == id) {
                prefs[Keys.ACTIVE_ID] = list.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setActive(id: String) {
        dataStore.edit { prefs -> prefs[Keys.ACTIVE_ID] = id }
    }

    /** 更新某配置最近一次延迟测试结果。 */
    suspend fun updatePing(id: String, pingMs: Int) {
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON]).map { p ->
                if (p.id == id) p.copy(pingMs = pingMs) else p
            }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
        }
    }

    /**
     * 批量导入配置（与现有配置合并；id 冲突时为导入项分配新 id）。
     * 导入内容来自扫码/剪贴板/文件等外部输入：先丢弃校验不通过的条目
     *（伪造/损坏的 kind、proto 等），返回实际导入条数，调用方据此提示用户。
     */
    suspend fun importProfiles(incoming: List<Profile>): Int {
        if (incoming.isEmpty()) return 0
        val valid = incoming.filter { it.validate() == null }
        if (valid.isEmpty()) return 0
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON]).toMutableList()
            val existingIds = list.map { it.id }.toMutableSet()
            for (profile in valid) {
                val resolved = if (existingIds.add(profile.id)) {
                    profile
                } else {
                    val re = profile.copy(id = java.util.UUID.randomUUID().toString())
                    existingIds.add(re.id)
                    re
                }
                list.add(resolved)
            }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
            val active = prefs[Keys.ACTIVE_ID]
            if (active.isNullOrEmpty() || list.none { it.id == active }) {
                prefs[Keys.ACTIVE_ID] = list.firstOrNull()?.id ?: ""
            }
        }
        return valid.size
    }

    /** 给指定配置累加本次会话的字节数。 */
    suspend fun addTraffic(id: String, txDelta: Long, rxDelta: Long) {
        if (txDelta <= 0 && rxDelta <= 0) return
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON]).map { p ->
                if (p.id == id) {
                    p.copy(txBytes = p.txBytes + txDelta, rxBytes = p.rxBytes + rxDelta)
                } else p
            }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
        }
    }

    /** 清空指定配置的累计流量。 */
    suspend fun resetTrafficFor(id: String) {
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON]).map { p ->
                if (p.id == id) p.copy(txBytes = 0L, rxBytes = 0L) else p
            }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
        }
    }

    /** 清空所有配置的累计流量（不影响配置本身）。 */
    suspend fun resetTraffic() {
        dataStore.edit { prefs ->
            val list = decodeList(prefs[Keys.PROFILES_JSON])
                .map { it.copy(txBytes = 0L, rxBytes = 0L) }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(listSerializer, list)
        }
    }

    private fun decodeList(stored: String?): List<Profile> =
        if (stored == null) emptyList()
        else runCatching { json.decodeFromString(listSerializer, stored) }.getOrDefault(emptyList())
}
