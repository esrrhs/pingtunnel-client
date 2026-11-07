package com.esrrhs.pingtunnel.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dnsLogStore by preferencesDataStore(name = "pingtunnel_dns_log")

/**
 * DNS 查询历史：与连接历史分库存储。
 *
 * DNS 会话量大（每秒数条），若与连接共用一个 1000 条的环形缓冲，真正的业务
 * 连接几分钟内就会被挤掉；这里单独一份、单独上限，互不影响。
 */
class DnsLogRepository(context: Context) {

    private val store = context.applicationContext.dnsLogStore
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ConnectionLogEntry.serializer())

    val entries: Flow<List<ConnectionLogEntry>> = store.data.map { prefs ->
        pruneConnectionLogs(
            decode(prefs[Keys.ENTRIES_JSON]),
            System.currentTimeMillis(),
            MAX_ENTRIES,
            MAX_AGE_MS,
        )
    }

    suspend fun append(finished: List<ConnectionLogEntry>, nowMs: Long) {
        if (finished.isEmpty()) return
        store.edit { prefs ->
            val current = decode(prefs[Keys.ENTRIES_JSON])
            val byKey = LinkedHashMap<String, ConnectionLogEntry>(current.size + finished.size)
            current.forEach { byKey[it.key + "@" + it.startMs] = it }
            finished.forEach { byKey[it.key + "@" + it.startMs] = it }
            prefs[Keys.ENTRIES_JSON] = json.encodeToString(
                serializer,
                pruneConnectionLogs(byKey.values, nowMs, MAX_ENTRIES, MAX_AGE_MS),
            )
        }
    }

    suspend fun clear() {
        store.edit { it.remove(Keys.ENTRIES_JSON) }
    }

    suspend fun delete(key: String, startMs: Long) {
        store.edit { prefs ->
            val list = decode(prefs[Keys.ENTRIES_JSON])
                .filterNot { it.key == key && it.startMs == startMs }
            prefs[Keys.ENTRIES_JSON] = json.encodeToString(serializer, list)
        }
    }

    private fun decode(stored: String?): List<ConnectionLogEntry> =
        if (stored == null) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
        }

    private object Keys {
        val ENTRIES_JSON = stringPreferencesKey("entries_json")
    }

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 1 天
    }
}
