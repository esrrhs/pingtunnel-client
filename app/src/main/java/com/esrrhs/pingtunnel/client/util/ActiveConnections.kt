package com.esrrhs.pingtunnel.client.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.esrrhs.pingtunnel.client.pingtunnel.PerAppMode
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.tun.HevTunnel
import java.net.InetSocketAddress

/** 一条经过 TUN 的传输层连接（hev 会话）。 */
data class LiveConnection(
    val key: String,
    val protocol: String,
    val srcIp: String,
    val srcPort: Int,
    val remoteIp: String,
    val remotePort: Int,
    /** hev mapped-DNS 反查到的真实目标域名（fake-IP 场景）；无则为 null。 */
    val domain: String?,
    val uid: Int,
    val txBytes: Long,
    val rxBytes: Long,
    val txRate: Long,
    val rxRate: Long,
    val createdMs: Long,
    /**
     * 是否走直连：命中域名直连规则的 TCP 会话由本地分流器直接走物理网络，
     * 不经 SPP 隧道（与 proxy/RuleSocksServer 的判定保持一致）。
     */
    val direct: Boolean,
    /** hev 会话表中是否仍存在；false 表示已关闭、处于短暂留痕展示窗口。 */
    val active: Boolean,
    /** 最近一次在 hev 会话表中看到该连接的时刻。 */
    val lastSeenMs: Long,
)

/** 同一 UID（应用）下的连接聚合。 */
data class AppConnectionGroup(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val connections: List<LiveConnection>,
    val txTotal: Long,
    val rxTotal: Long,
    val txRate: Long,
    val rxRate: Long,
    /** 组内是否还有活跃连接（全为留痕连接时用于排序降权）。 */
    val hasActive: Boolean,
    /** 组内最早一条现存连接的创建时刻。 */
    val firstSeenMs: Long,
)

/** 已从原生会话表消失、但仍在留痕窗口内的连接（归属信息一并缓存）。 */
data class RetainedConn(
    val conn: LiveConnection,
    val packageName: String?,
    val label: String,
    val lastSeenMs: Long,
)

/** 一次采样中观测到的单条会话（UID 已反查、归属已过滤）。 */
data class HistoryRow(
    val key: String,
    val uid: Int,
    val packageName: String?,
    val label: String,
    /** 展示用目标：优先 mapped-DNS 域名，否则 IP:端口。 */
    val destination: String,
    val domain: String?,
    val remoteIp: String,
    val remotePort: Int,
    val proto: String,
    val txBytes: Long,
    val rxBytes: Long,
    val direct: Boolean,
    val createdMs: Long,
)

data class ConnectionSample(
    val groups: List<AppConnectionGroup>,
    /** connection key → 上轮累计 (tx, rx)，用于差分速率。 */
    val prevBytes: Map<String, Pair<Long, Long>>,
    /** 留痕窗口内最近消失的连接，key 为连接五元组。 */
    val retained: Map<String, RetainedConn>,
    /** 本轮仍活跃的会话行（供全局连接记录器做生命周期跟踪）。 */
    val liveRows: List<HistoryRow> = emptyList(),
)

/**
 * 当前隧道连接快照，数据源为 hev-socks5-tunnel 导出的会话表：
 * hev 在 TUN 协议栈握有每条连接的原始五元组、payload 字节计数与创建时间；
 * App 归属通过 [ConnectivityManager.getConnectionOwnerUid]（Android 10+）
 * 反查五元组对应应用 UID（VPN 应用专用 API，无需特权）。
 *
 * 手机上大量 HTTP 请求是 1~2 秒内结束的短连接，轮询瞬间往往已经关闭，
 * 因此把刚消失的连接在 [RETAIN_MS] 窗口内以「已结束」状态继续展示，
 * 避免页面只看到零星几条甚至空白。
 */
object ActiveConnections {

    /** 已关闭连接继续留痕展示的时长。 */
    const val RETAIN_MS = 6000L

    fun empty(): ConnectionSample = ConnectionSample(emptyList(), emptyMap(), emptyMap())

    fun snapshot(
        context: Context,
        profile: Profile?,
        prev: ConnectionSample?,
        nowMs: Long,
        intervalMs: Long,
        /**
         * 当前生效的用户态分流判定器（域名直连 + CN CIDR + 绕过局域网）。
         * 与 RuleSocksServer 使用同一份规则构建，保证标记与真实路径一致；
         * 无规则（纯全局代理）时传 [DirectClassifier.EMPTY]。
         */
        direct: DirectClassifier = DirectClassifier.EMPTY,
        /**
         * 调用方在本轮已取过的 hev 原生会话原文（含诊断日志等复用场景）：
         * 透传可避免每 tick 重复一次 256KB JNI 缓冲填充与原生会话表枚举。
         */
        rawSessions: String? = null,
    ): ConnectionSample {
        val sessions = parseSessions(rawSessions ?: HevTunnel.sessions().orEmpty())

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val pm = context.packageManager
        val allowedPackages = profile?.perAppPackages?.toSet().orEmpty()

        data class Temp(
            val conn: LiveConnection,
            val packageName: String?,
            val label: String,
        )

        // 本轮仍活跃的连接（归属反查 + 分应用过滤 + 速率差分）
        val activeRows = sessions.mapNotNull { s ->
            val uid = ownerUid(cm, s)
            val info = resolveAppInfo(context, uid)
            val pkg = info.packageName
            // 按配置的分应用规则过滤
            val routed = when (profile?.perAppMode ?: PerAppMode.ALL) {
                PerAppMode.ALL -> true
                PerAppMode.ALLOWED -> pkg != null && pkg in allowedPackages
                PerAppMode.DISALLOWED -> pkg == null || pkg !in allowedPackages
            }
            if (!routed) return@mapNotNull null

            val label = info.label
            val prevPair = prev?.prevBytes?.get(s.key)
            Temp(
                conn = LiveConnection(
                    key = s.key,
                    protocol = if (s.proto == 6) "TCP" else "UDP",
                    srcIp = s.srcIp,
                    srcPort = s.srcPort,
                    remoteIp = s.dstIp,
                    remotePort = s.dstPort,
                    domain = s.domain,
                    uid = uid,
                    txBytes = s.upload,
                    rxBytes = s.download,
                    txRate = rateBetween(prevPair?.first, s.upload, intervalMs),
                    rxRate = rateBetween(prevPair?.second, s.download, intervalMs),
                    createdMs = s.createdMs,
                    direct = direct.isDirectSession(s.domain, s.dstIp),
                    active = true,
                    lastSeenMs = nowMs,
                ),
                packageName = pkg,
                label = label,
            )
        }

        val activeKeys = activeRows.mapTo(HashSet()) { it.conn.key }

        // 上轮留痕连接：本轮重新出现的丢弃（用活跃行替代），超出窗口的淘汰
        val carriedRetained = prev?.retained.orEmpty().filter { (key, rc) ->
            key !in activeKeys && nowMs - rc.lastSeenMs <= RETAIN_MS
        }

        val rows = ArrayList<Temp>(activeRows.size + carriedRetained.size)
        rows.addAll(activeRows)
        carriedRetained.forEach { (_, rc) ->
            rows.add(
                Temp(
                    // 留痕连接冻结计数与速率
                    conn = rc.conn.copy(active = false, txRate = 0L, rxRate = 0L),
                    packageName = rc.packageName,
                    label = rc.label,
                ),
            )
        }

        val groups = rows.groupBy { it.conn.uid }.values.map { groupRows ->
            val first = groupRows.first()
            val conns = groupRows.map { it.conn }.sortedWith(
                compareByDescending<LiveConnection> { it.active }
                    .thenByDescending { it.lastSeenMs }
                    .thenBy { it.protocol },
            )
            AppConnectionGroup(
                uid = first.conn.uid,
                packageName = first.packageName,
                label = first.label,
                connections = conns,
                txTotal = conns.sumOf { it.txBytes },
                rxTotal = conns.sumOf { it.rxBytes },
                txRate = conns.sumOf { it.txRate },
                rxRate = conns.sumOf { it.rxRate },
                hasActive = conns.any { it.active },
                firstSeenMs = conns.minOf { it.createdMs },
            )
        }.sortedWith(
            compareByDescending<AppConnectionGroup> { it.hasActive }
                .thenByDescending { it.txRate + it.rxRate }
                .thenBy { it.label.lowercase() },
        )

        val bytesNow = activeRows.associate {
            it.conn.key to (it.conn.txBytes to it.conn.rxBytes)
        }
        // 下轮留痕表：本轮活跃连接（以最新计数续期）+ 仍在窗口内的历史留痕
        val nextRetained = LinkedHashMap<String, RetainedConn>().apply {
            putAll(carriedRetained)
            activeRows.forEach { t ->
                put(t.conn.key, RetainedConn(t.conn, t.packageName, t.label, nowMs))
            }
        }
        val liveRows = activeRows.map { t ->
            val c = t.conn
            HistoryRow(
                key = c.key,
                uid = c.uid,
                packageName = t.packageName,
                label = t.label,
                destination = c.domain ?: "${c.remoteIp}:${c.remotePort}",
                domain = c.domain,
                remoteIp = c.remoteIp,
                remotePort = c.remotePort,
                proto = c.protocol,
                txBytes = c.txBytes,
                rxBytes = c.rxBytes,
                direct = c.direct,
                createdMs = c.createdMs,
            )
        }
        return ConnectionSample(groups, bytesNow, nextRetained, liveRows)
    }

    data class AppInfo(
        val packageName: String?,
        val label: String,
    )

    private val appInfoCache = java.util.concurrent.ConcurrentHashMap<Int, AppInfo>()

    fun clearAppCache() {
        appInfoCache.clear()
    }

    private fun resolveAppInfo(context: Context, uid: Int): AppInfo {
        if (uid < 0) {
            return AppInfo(null, context.getString(com.esrrhs.pingtunnel.client.R.string.conn_unknown_app, uid))
        }
        return appInfoCache.computeIfAbsent(uid) {
            val pm = context.packageManager
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            val label = pkg?.let {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
                    .getOrDefault(it)
            } ?: context.getString(com.esrrhs.pingtunnel.client.R.string.conn_unknown_app, uid)
            AppInfo(pkg, label)
        }
    }

    /**
     * 供连接历史采样：解析当前会话并反查 UID/应用（含分应用过滤），
     * 不做速率差分与留痕，调用方自行维护跨轮次状态（见 ConnectionLogMerge）。
     */
    fun historyRows(
        context: Context,
        profile: Profile?,
        direct: DirectClassifier = DirectClassifier.EMPTY,
        /** 同 [snapshot]：复用调用方已取的原生会话原文，避免重复 JNI 枚举。 */
        rawSessions: String? = null,
    ): List<HistoryRow> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val allowedPackages = profile?.perAppPackages?.toSet().orEmpty()
        return parseSessions(rawSessions ?: HevTunnel.sessions().orEmpty()).mapNotNull { s ->
            val uid = ownerUid(cm, s)
            val info = resolveAppInfo(context, uid)
            val pkg = info.packageName
            val routed = when (profile?.perAppMode ?: PerAppMode.ALL) {
                PerAppMode.ALL -> true
                PerAppMode.ALLOWED -> pkg != null && pkg in allowedPackages
                PerAppMode.DISALLOWED -> pkg == null || pkg !in allowedPackages
            }
            if (!routed) return@mapNotNull null
            HistoryRow(
                key = s.key,
                uid = uid,
                packageName = pkg,
                label = info.label,
                destination = s.domain ?: "${s.dstIp}:${s.dstPort}",
                domain = s.domain,
                remoteIp = s.dstIp,
                remotePort = s.dstPort,
                proto = if (s.proto == 6) "TCP" else "UDP",
                txBytes = s.upload,
                rxBytes = s.download,
                direct = direct.isDirectSession(s.domain, s.dstIp),
                createdMs = s.createdMs,
            )
        }
    }

    private fun rateBetween(old: Long?, new: Long, intervalMs: Long): Long {
        if (old == null || new < old || intervalMs <= 0L) return 0L
        return (new - old) * 1000L / intervalMs
    }

    private fun ownerUid(cm: ConnectivityManager?, s: RawSession): Int {
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            // 找不到归属时返回 -1（系统内部 UNKNOWN_UID，非公开常量）
            cm.getConnectionOwnerUid(
                s.proto,
                InetSocketAddress(s.srcIp, s.srcPort),
                InetSocketAddress(s.dstIp, s.dstPort),
            )
        }.getOrDefault(-1)
    }

    // ---- 原生会话文本解析（纯函数，便于单测） ----

    internal data class RawSession(
        val key: String,
        val proto: Int,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val upload: Long,
        val download: Long,
        val createdMs: Long,
        val domain: String?,
    )

    /**
     * 解析 hev 导出的行：
     * proto|srcIp|srcPort|dstIp|dstPort|upload|download|createdMs[|domain]。
     * 第 9 字段 domain 为 mapped-DNS 反查到的真实域名，可能缺失或为空。
     * 非法行跳过。
     */
    internal fun parseSessions(text: String): List<RawSession> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.trim().split('|')
            if (p.size !in 8..9) return@mapNotNull null
            val proto = p[0].toIntOrNull()?.takeIf { it == 6 || it == 17 }
                ?: return@mapNotNull null
            val srcPort = p[2].toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
            val dstPort = p[4].toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
            val upload = p[5].toLongOrNull() ?: return@mapNotNull null
            val download = p[6].toLongOrNull() ?: return@mapNotNull null
            val createdMs = p[7].toLongOrNull() ?: return@mapNotNull null
            RawSession(
                key = "$proto|${p[1]}:$srcPort->${p[3]}:$dstPort",
                proto = proto,
                srcIp = p[1],
                srcPort = srcPort,
                dstIp = p[3],
                dstPort = dstPort,
                upload = upload,
                download = download,
                createdMs = createdMs,
                domain = p.getOrNull(8)?.trim()?.takeIf { it.isNotBlank() },
            )
        }.toList()
    }
}
