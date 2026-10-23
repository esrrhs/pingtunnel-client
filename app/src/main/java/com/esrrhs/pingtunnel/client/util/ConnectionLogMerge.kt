package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_FAILED
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_SUCCESS
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_UNKNOWN
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_DIRECT
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_PROXY

/** 分流器建连结果的最小投影（避免 util 层直接依赖 proxy 包）。 */
data class ConnectEventInfo(
    val direct: Boolean,
    val success: Boolean,
    val reason: String?,
    val connectMs: Int,
)

/** 按目标（域名或 IP + 端口、时间窗）查找建连事件。 */
fun interface ConnectEventFinder {
    fun find(host: String?, port: Int, notBeforeMs: Long): ConnectEventInfo?
}

/**
 * 单连接生命周期跟踪（纯逻辑，便于单测）。
 *
 * - 每轮把仍存活的 hev 会话刷新为 active（更新字节、方式、结果）；
 * - 会话从采样中消失即判定结束：有失败事件记失败（附原因），
 *   无事件时按是否产生过 payload 粗分为成功/未知（UDP 通常为未知）。
 */
object ConnectionLogMerge {

    /** 建连事件可能比 hev 会话首次被采样早若干秒。 */
    const val EVENT_LOOKBACK_MS = 8000L

    data class State(val active: Map<String, ConnectionLogEntry> = emptyMap())

    data class Tick(
        val finished: List<ConnectionLogEntry>,
        val state: State,
    )

    fun tick(
        state: State,
        rows: List<HistoryRow>,
        nowMs: Long,
        finder: ConnectEventFinder,
        proxyName: String? = null,
        proxyServer: String? = null,
    ): Tick {
        val nextActive = LinkedHashMap<String, ConnectionLogEntry>(state.active.size + rows.size)
        val finished = ArrayList<ConnectionLogEntry>()
        val rowKeys = HashSet<String>(rows.size)

        for (row in rows) {
            rowKeys.add(row.key)
            val existing = state.active[row.key]
            val entry = mergeRow(existing, row, finder, proxyName, proxyServer)
            nextActive[row.key] = entry
        }

        for ((key, entry) in state.active) {
            if (key in rowKeys) continue
            finished += finalize(entry, nowMs, finder)
        }
        return Tick(finished, State(nextActive))
    }

    /** VPN 停止时把所有未结束会话一次性收尾。 */
    fun finishAll(state: State, nowMs: Long, finder: ConnectEventFinder): List<ConnectionLogEntry> =
        state.active.values.map { finalize(it, nowMs, finder) }

    private fun mergeRow(
        existing: ConnectionLogEntry?,
        row: HistoryRow,
        finder: ConnectEventFinder,
        proxyName: String?,
        proxyServer: String?,
    ): ConnectionLogEntry {
        val startMs = existing?.startMs ?: row.createdMs
        val event = finder.find(row.domain ?: row.remoteIp, row.remotePort, startMs - EVENT_LOOKBACK_MS)

        val route = when {
            event != null -> if (event.direct) ROUTE_DIRECT else ROUTE_PROXY
            existing != null && existing.route != ConnectionLogEntry.ROUTE_UNKNOWN -> existing.route
            row.direct -> ROUTE_DIRECT
            else -> ROUTE_PROXY
        }

        // 失败一旦确认不再被后续轮次覆盖；结果未知时可被成功事件升级
        val result = when {
            existing?.result == RESULT_FAILED -> RESULT_FAILED
            event != null -> if (event.success) RESULT_SUCCESS else RESULT_FAILED
            existing != null -> existing.result
            else -> RESULT_UNKNOWN
        }
        val reason = when {
            existing?.result == RESULT_FAILED -> existing.reason
            event != null && !event.success -> event.reason
            existing != null -> existing.reason
            else -> null
        }
        val connectMs = event?.connectMs ?: existing?.connectMs

        return (existing ?: newEntry(row, startMs)).copy(
            packageName = row.packageName ?: existing?.packageName,
            label = row.label,
            domain = row.domain ?: existing?.domain,
            route = route,
            // 仅代理流记录所属配置；直连流置空。故障切换到另一配置时随当前采样刷新
            proxyName = if (route == ROUTE_PROXY) proxyName else null,
            proxyServer = if (route == ROUTE_PROXY) proxyServer else null,
            result = result,
            reason = reason,
            connectMs = connectMs,
            txBytes = row.txBytes,
            rxBytes = row.rxBytes,
            endMs = 0,
        )
    }

    private fun finalize(
        entry: ConnectionLogEntry,
        nowMs: Long,
        finder: ConnectEventFinder,
    ): ConnectionLogEntry {
        if (entry.result == RESULT_FAILED) {
            return entry.copy(endMs = nowMs)
        }
        val event = finder.find(entry.domain ?: entry.remoteIp, entry.remotePort,
            entry.startMs - EVENT_LOOKBACK_MS)
        return if (event != null && !event.success) {
            entry.copy(
                route = if (event.direct) ROUTE_DIRECT else ROUTE_PROXY,
                result = RESULT_FAILED,
                reason = event.reason,
                connectMs = event.connectMs,
                endMs = nowMs,
            )
        } else {
            val inferred = if (entry.txBytes > 0 || entry.rxBytes > 0) RESULT_SUCCESS else RESULT_UNKNOWN
            entry.copy(result = inferred, endMs = nowMs)
        }
    }

    private fun newEntry(row: HistoryRow, startMs: Long) = ConnectionLogEntry(
        key = row.key,
        uid = row.uid,
        packageName = row.packageName,
        label = row.label,
        proto = row.proto,
        domain = row.domain,
        remoteIp = row.remoteIp,
        remotePort = row.remotePort,
        route = if (row.direct) ROUTE_DIRECT else ROUTE_PROXY,
        startMs = startMs,
    )
}
