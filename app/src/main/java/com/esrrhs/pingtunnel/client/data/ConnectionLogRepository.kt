package com.esrrhs.pingtunnel.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.connectionLogStore by preferencesDataStore(name = "pingtunnel_connection_log")

/** 单连接历史持久化：最多 [MAX_ENTRIES] 条、保留 [MAX_AGE_MS]，按结束时间倒序。 */
class ConnectionLogRepository(context: Context) {

    private val store = context.applicationContext.connectionLogStore
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ConnectionLogEntry.serializer())

    val entries: Flow<List<ConnectionLogEntry>> = store.data.map { prefs ->
        pruneConnectionLogs(decode(prefs[Keys.ENTRIES_JSON]), System.currentTimeMillis())
    }

    /** 追加已结束的记录并裁剪；同 key 的旧记录会被新记录覆盖。 */
    suspend fun append(finished: List<ConnectionLogEntry>, nowMs: Long) {
        if (finished.isEmpty()) return
        store.edit { prefs ->
            val current = decode(prefs[Keys.ENTRIES_JSON])
            val byKey = LinkedHashMap<String, ConnectionLogEntry>(current.size + finished.size)
            current.forEach { byKey[it.key + "@" + it.startMs] = it }
            finished.forEach { byKey[it.key + "@" + it.startMs] = it }
            prefs[Keys.ENTRIES_JSON] = json.encodeToString(
                serializer,
                pruneConnectionLogs(byKey.values, nowMs),
            )
        }
    }

    /** 把已有记录裁到上限并写回，避免旧数据一直占着 DataStore 内存。 */
    suspend fun compact(nowMs: Long) {
        store.edit { prefs ->
            val current = decode(prefs[Keys.ENTRIES_JSON])
            val pruned = pruneConnectionLogs(current, nowMs)
            if (pruned.size != current.size) {
                prefs[Keys.ENTRIES_JSON] = json.encodeToString(serializer, pruned)
            }
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
        const val MAX_ENTRIES = 1000
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 天
    }
}

/** 最近 [ConnectionLogRepository.MAX_ENTRIES] 条，且不超过 [ConnectionLogRepository.MAX_AGE_MS]。 */
internal fun pruneConnectionLogs(
    entries: Collection<ConnectionLogEntry>,
    nowMs: Long,
): List<ConnectionLogEntry> =
    entries.asSequence()
        .filter { nowMs - maxOf(it.endMs, it.startMs) <= ConnectionLogRepository.MAX_AGE_MS }
        .sortedByDescending { maxOf(it.endMs, it.startMs) }
        .take(ConnectionLogRepository.MAX_ENTRIES)
        .toList()
