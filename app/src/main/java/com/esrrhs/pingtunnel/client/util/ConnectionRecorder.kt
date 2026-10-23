package com.esrrhs.pingtunnel.client.util

import android.content.Context
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry
import java.io.File
import com.esrrhs.pingtunnel.client.data.ConnectionLogRepository
import com.esrrhs.pingtunnel.client.proxy.ProxyEventBus
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.tun.HevTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局连接采集器：VPN 已连接期间由 PingtunnelVpnService 驱动。
 *
 * 采样策略（固定 1.5s 高频）：
 * - 始终以 1.5s 间隔枚举会话并维护历史生命周期，保证切回「当前连接」页时
 *   列表与速率即时可用、短连接（1~2s）也能被采到——曾尝试过后台 5s/灭屏 15s
 *   的自适应降频，因损失实时性已在 b167223 回退；
 * - 无 UI 观察时仍采样轻量会话行供历史记录，但跳过 groups 排序与速率差分；
 * - 磁盘日志截断每 5 分钟一次，避免频繁闪存 I/O。
 */
object ConnectionRecorder {

    private const val TICK_MS = 1500L
    private const val SAVE_INTERVAL_MS = 3000L
    private const val TRIM_INTERVAL_MS = 300_000L // 5 分钟

    private val _liveGroups = MutableStateFlow<List<AppConnectionGroup>>(emptyList())
    val liveGroups: StateFlow<List<AppConnectionGroup>> = _liveGroups.asStateFlow()

    private var job: Job? = null
    private var sample: ConnectionSample = ActiveConnections.empty()
    private var state: ConnectionLogMerge.State = ConnectionLogMerge.State()
    private var pending = ArrayList<ConnectionLogEntry>()
    private var lastSaveMs = 0L
    private var lastTrimMs = 0L

    private val eventFinder = ConnectEventFinder { host, port, notBefore ->
        ProxyEventBus.find(host, port, notBefore)?.let {
            ConnectEventInfo(it.direct, it.success, it.reason, it.durationMs)
        }
    }

    /** 开始一轮 VPN 会话的采集；[direct] 在本次会话内固定（与 RuleSocksServer 同源）。 */
    fun start(
        scope: CoroutineScope,
        context: Context,
        profile: Profile,
        direct: DirectClassifier,
    ) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        sample = ActiveConnections.empty()
        state = ConnectionLogMerge.State()
        pending = ArrayList()
        lastSaveMs = System.currentTimeMillis()
        lastTrimMs = System.currentTimeMillis()
        _liveGroups.value = emptyList()

        job = scope.launch(Dispatchers.IO) {
            val repository = ConnectionLogRepository(appContext)
            var lastMs = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val interval = now - lastMs
                val isUiObserving = _liveGroups.subscriptionCount.value > 0

                // 每 tick 只做一次原生会话枚举（256KB JNI 缓冲 + 会话表遍历），
                // 诊断日志与下方采样共用同一份原文。
                val rawSessions = HevTunnel.sessions().orEmpty()

                // 诊断：记录 hev 原生会话原文，排查长流为何不出现在连接列表。
                // logd 单条负载约 4KB，整串直打会被静默截断——按行边界分块、总量封顶。
                LogcatChunks.chunk("uiObserve=$isUiObserving sessions=$rawSessions")
                    .forEach { chunk -> android.util.Log.i("ConnRecDiag", chunk) }

                val liveRows = if (isUiObserving) {
                    sample = ActiveConnections.snapshot(
                        context = appContext,
                        profile = profile,
                        prev = sample,
                        nowMs = now,
                        intervalMs = interval,
                        direct = direct,
                        rawSessions = rawSessions,
                    )
                    _liveGroups.value = sample.groups
                    sample.liveRows
                } else {
                    // 没有 UI 观察当前连接，完全跳过 groups 排序、速率差分与留痕计算
                    ActiveConnections.historyRows(
                        context = appContext,
                        profile = profile,
                        direct = direct,
                        rawSessions = rawSessions,
                    )
                }

                val tick = ConnectionLogMerge.tick(
                    state,
                    liveRows,
                    now,
                    eventFinder,
                    proxyName = profile.name,
                    proxyServer = profile.config.serverHost,
                )
                state = tick.state
                if (tick.finished.isNotEmpty()) {
                    pending.addAll(tick.finished)
                }
                if (now - lastSaveMs >= SAVE_INTERVAL_MS) {
                    if (pending.isNotEmpty()) {
                        repository.append(pending, now)
                        pending.clear()
                    }
                    lastSaveMs = now
                }
                if (now - lastTrimMs >= TRIM_INTERVAL_MS) {
                    lastTrimMs = now
                    trimRuntimeLogs(appContext)
                }
                lastMs = now

                delay(TICK_MS)
            }
        }
    }

    /** 停止采集：把残留会话收尾并立即落盘（在 service 协程内调用）。 */
    suspend fun stop(context: Context) {
        job?.cancel()
        job = null
        val now = System.currentTimeMillis()
        val tail = ConnectionLogMerge.finishAll(state, now, eventFinder)
        state = ConnectionLogMerge.State()
        _liveGroups.value = emptyList()
        withContext(Dispatchers.IO) {
            val all = pending + tail
            if (all.isNotEmpty()) {
                ConnectionLogRepository(context.applicationContext).append(all, now)
            }
        }
        pending.clear()
        sample = ActiveConnections.empty()
        ActiveConnections.clearAppCache()
        trimRuntimeLogs(context.applicationContext)
    }

    /** hev 以 O_APPEND 持续写 hev.log，连接期间定期截断，停掉后再收一次尾。 */
    private fun trimRuntimeLogs(context: Context) {
        val dir = context.filesDir
        RuntimeLogs.trim(File(dir, "hev.log"))
        RuntimeLogs.trim(File(dir, "pingtunnel.log"))
    }
}
