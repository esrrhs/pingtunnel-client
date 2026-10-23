package com.esrrhs.pingtunnel.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esrrhs.pingtunnel.client.data.AppSettings
import com.esrrhs.pingtunnel.client.data.ConfigRepository
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry
import com.esrrhs.pingtunnel.client.data.ConnectionLogRepository
import com.esrrhs.pingtunnel.client.data.SettingsRepository
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelProcess
import com.esrrhs.pingtunnel.client.pingtunnel.ValidationError
import com.esrrhs.pingtunnel.client.tun.HevTunnel
import com.esrrhs.pingtunnel.client.util.LeakCheck
import com.esrrhs.pingtunnel.client.util.SocksProbe
import com.esrrhs.pingtunnel.client.util.TrafficMeter
import com.esrrhs.pingtunnel.client.util.TunnelCheck
import com.esrrhs.pingtunnel.client.vpn.ActiveSession
import com.esrrhs.pingtunnel.client.vpn.VpnController
import com.esrrhs.pingtunnel.client.vpn.VpnState
import com.esrrhs.pingtunnel.client.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连接自检状态。 */
sealed interface SelfCheckState {
    data object Idle : SelfCheckState
    data object Running : SelfCheckState
    data class Done(val result: TunnelCheck.Result) : SelfCheckState
}

/** 防泄漏检测状态。 */
sealed interface LeakState {
    data object Idle : LeakState
    data object Running : LeakState
    data class Done(val report: LeakCheck.Report) : LeakState
}

/** 查 IP 状态：直连出口与（已连接时的）隧道出口归属地。 */
sealed interface IpQueryState {
    data object Idle : IpQueryState
    data object Running : IpQueryState
    data class Done(
        val direct: com.esrrhs.pingtunnel.client.util.IpGeoInfo?,
        val tunnel: com.esrrhs.pingtunnel.client.util.IpGeoInfo?,
    ) : IpQueryState
}

/**
 * 当前会话的实时数据（叠加在 profile 已累计值之上显示）。
 * [tx]/[rx] 为本轮隧道累计字节；[txRate]/[rxRate] 为实时速率（字节/秒）；
 * [connectedAtMs] 为本次连接建立时刻，用于时长展示。
 */
data class SessionTraffic(
    val tx: Long = 0,
    val rx: Long = 0,
    val txRate: Long = 0,
    val rxRate: Long = 0,
    val connectedAtMs: Long? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ConfigRepository(app)
    private val settingsRepository = SettingsRepository(app)

    val profiles: StateFlow<List<Profile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String?> = repository.activeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val emptySession = SessionTraffic()
    private val _session = MutableStateFlow(emptySession)
    val session: StateFlow<SessionTraffic> = _session

    private val trafficMeter = TrafficMeter()
    private var connectedAtMs: Long? = null

    /** 正在进行单个延迟测试的配置 id（用于按钮转圈反馈）。 */
    private val _testingPingId = MutableStateFlow<String?>(null)
    val testingPingId: StateFlow<String?> = _testingPingId

    private val _selfCheck = MutableStateFlow<SelfCheckState>(SelfCheckState.Idle)
    val selfCheck: StateFlow<SelfCheckState> = _selfCheck
    private val selfCheckGate = GenerationGate()

    /** 已连接时对当前隧道做出口 IP / DNS 路径自检。 */
    fun runSelfCheck() {
        if (_selfCheck.value is SelfCheckState.Running) return
        val port = ActiveSession.socksPort
        if (VpnStateHolder.state.value !is VpnState.Connected || port == null) return
        viewModelScope.launch {
            _selfCheck.value = SelfCheckState.Running
            val token = selfCheckGate.token()
            val result = withContext(Dispatchers.IO) { TunnelCheck.run(port) }
            // 用户已关闭弹窗则丢弃本次结果，不复活对话框
            if (selfCheckGate.isCurrent(token)) _selfCheck.value = SelfCheckState.Done(result)
        }
    }

    fun dismissSelfCheck() {
        selfCheckGate.bump()
        _selfCheck.value = SelfCheckState.Idle
    }

    private val _leak = MutableStateFlow<LeakState>(LeakState.Idle)
    val leak: StateFlow<LeakState> = _leak

    /** IPv4/IPv6/DNS 防泄漏专项检测（经当前隧道）。 */
    fun runLeakCheck() {
        if (_leak.value is LeakState.Running) return
        val port = ActiveSession.socksPort
        if (VpnStateHolder.state.value !is VpnState.Connected || port == null) return
        viewModelScope.launch {
            _leak.value = LeakState.Running
            val token = leakGate.token()
            val report = withContext(Dispatchers.IO) { LeakCheck.run(port) }
            if (leakGate.isCurrent(token)) _leak.value = LeakState.Done(report)
        }
    }

    private val leakGate = GenerationGate()

    private val _ipQuery = MutableStateFlow<IpQueryState>(IpQueryState.Idle)
    val ipQuery: StateFlow<IpQueryState> = _ipQuery
    private val ipQueryGate = GenerationGate()

    /** 查询直连出口与隧道出口 IP 归属地。 */
    fun runIpQuery() {
        if (_ipQuery.value is IpQueryState.Running) return
        viewModelScope.launch {
            _ipQuery.value = IpQueryState.Running
            val token = ipQueryGate.token()
            val language = if (getLanguage() == "zh") "zh-CN" else "en"
            val direct = withContext(Dispatchers.IO) {
                runCatching { com.esrrhs.pingtunnel.client.util.IpQuery.queryDirect(language) }
                    .getOrNull()
            }
            val port = ActiveSession.socksPort
            val tunnel = if (VpnStateHolder.state.value is VpnState.Connected && port != null) {
                withContext(Dispatchers.IO) {
                    runCatching { com.esrrhs.pingtunnel.client.util.IpQuery.queryViaSocks(port, language) }
                        .getOrNull()
                }
            } else {
                null
            }
            if (ipQueryGate.isCurrent(token)) _ipQuery.value = IpQueryState.Done(direct, tunnel)
        }
    }

    fun dismissIpQuery() {
        ipQueryGate.bump()
        _ipQuery.value = IpQueryState.Idle
    }

    // ---- 单连接历史：采集在 Service 层全局进行（ConnectionRecorder），这里只读仓库 ----

    private val connectionLogRepository = ConnectionLogRepository(app)

    val connectionLogs: StateFlow<List<ConnectionLogEntry>> =
        connectionLogRepository.entries
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearConnectionLogs() {
        viewModelScope.launch { connectionLogRepository.clear() }
    }

    fun deleteConnectionLog(entry: ConnectionLogEntry) {
        viewModelScope.launch { connectionLogRepository.delete(entry.key, entry.startMs) }
    }

    private fun getLanguage(): String {
        val locales = getApplication<Application>().resources.configuration.locales
        return if (locales.isEmpty) "en" else locales[0].language
    }

    init {
        viewModelScope.launch { pollSessionTraffic() }
        viewModelScope.launch { connectionLogRepository.compact(System.currentTimeMillis()) }
    }

    /**
     * 点击某条配置：若它正在连接/已连接则断开；否则选中并连接。
     */
    fun onProfileClicked(id: String) {
        val current = VpnStateHolder.state.value
        if (id == activeId.value &&
            (current is VpnState.Connected || current is VpnState.Connecting)
        ) {
            VpnController.disconnect(getApplication())
        } else {
            viewModelScope.launch {
                repository.setActive(id)
                VpnController.connect(getApplication())
            }
        }
    }

    /** 仅切换选中配置，不自动连接（Shadowsocks 风格：选完再按大圆钮）。 */
    fun selectProfile(id: String) {
        viewModelScope.launch { repository.setActive(id) }
    }

    fun saveProfile(profile: Profile, onResult: (ValidationError?) -> Unit) {
        viewModelScope.launch {
            // 新建配置套用全局默认「绕过局域网」
            val toSave = if (profiles.value.none { it.id == profile.id } &&
                settings.value.defaultBypassLan && !profile.bypassLan
            ) {
                profile.copy(bypassLan = true)
            } else {
                profile
            }
            val error = toSave.validate()
            if (error == null) repository.upsert(toSave)
            onResult(error)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch { settingsRepository.save(settings) }
    }

    /**
     * 单个配置延迟测试：经 pingtunnel 隧道发起真实 SOCKS5 建连（含远端 DNS）。
     * 已连接的当前配置直接走运行中的隧道；其它配置临时拉起 pingtunnel client 实测后关闭。
     * [onResult] 返回测得毫秒数，失败/超时返回 -1，便于界面给出反馈。
     */
    fun testPing(id: String, onResult: (Int) -> Unit = {}) {
        if (_testingPingId.value != null) return
        viewModelScope.launch {
            val profile = profiles.value.firstOrNull { it.id == id } ?: return@launch
            _testingPingId.value = id
            try {
                val ms = measureRealLatency(profile)
                repository.updatePing(id, ms)
                onResult(ms)
            } finally {
                _testingPingId.value = null
            }
        }
    }

    /**
     * 返回经隧道 CONNECT 到稳定目标的耗时（ms），失败 -1。
     * 当前活动配置复用已运行端口；其余配置临时拉起 pingtunnel client，
     * 因此错误的 key/加密或不可达 server 都会得到 -1。
     * 手动测速使用单次采样、较短超时，避免服务器不可达时长时间无响应。
     */
    private suspend fun measureRealLatency(profile: Profile): Int =
        withContext(Dispatchers.IO) {
            val activePort = ActiveSession.socksPort
            val isActive = profile.id == activeId.value &&
                VpnStateHolder.state.value is VpnState.Connected && activePort != null
            if (isActive) {
                SocksProbe.measure(activePort!!, samples = 1, timeoutMs = PING_TIMEOUT_MS)
            } else {
                var proc: PingtunnelProcess? = null
                try {
                    proc = PingtunnelProcess(getApplication())
                    val port = proc.start(profile.config, PING_START_TIMEOUT_MS)
                    SocksProbe.measure(port, samples = 1, timeoutMs = PING_TIMEOUT_MS)
                } catch (e: Exception) {
                    -1
                } finally {
                    proc?.stop()
                }
            }
        }

    /** 导入扫码得到的配置。 */
    fun importScanned(profile: Profile) {
        viewModelScope.launch {
            repository.importProfiles(listOf(profile))
            val stored = repository.profiles.first()
            val match = stored.lastOrNull {
                it.name == profile.name && it.config.serverHost == profile.config.serverHost
            }
            if (match != null) repository.setActive(match.id)
        }
    }

    suspend fun importProfiles(list: List<Profile>) = repository.importProfiles(list)

    /** 清空所有配置的累计流量统计。 */
    fun resetTraffic() {
        viewModelScope.launch { repository.resetTraffic() }
    }

    /** 清空指定配置的累计流量统计。 */
    fun resetTrafficFor(id: String) {
        viewModelScope.launch { repository.resetTrafficFor(id) }
    }

    /** 返回当前选中配置 id（无显式选择时取第一条）；无配置返回 null。 */
    suspend fun awaitActiveProfileId(): String? {
        val list = repository.profiles.first()
        val active = repository.activeId.first()
        return list.firstOrNull { it.id == active }?.id ?: list.firstOrNull()?.id
    }

    /**
     * 已连接时每秒取隧道计数，展示本会话字节与实时速率；
     * 重连中保持上一次数据；断开/出错则清零。
     */
    private suspend fun pollSessionTraffic() {
        while (true) {
            val state = VpnStateHolder.state.value
            when {
                state is VpnState.Connected -> {
                    val raw = HevTunnel.stats()
                    if (raw != null) {
                        val now = System.currentTimeMillis()
                        val tx = raw.getOrNull(1) ?: 0L
                        val rx = raw.getOrNull(3) ?: 0L
                        if (connectedAtMs == null) {
                            connectedAtMs = now
                            trafficMeter.rebaseline(tx, rx, now)
                        }
                        val rates = trafficMeter.update(tx, rx, now)
                        _session.value = SessionTraffic(
                            tx = tx,
                            rx = rx,
                            txRate = rates.txBytesPerSec,
                            rxRate = rates.rxBytesPerSec,
                            connectedAtMs = connectedAtMs,
                        )
                    }
                }
                // 重连过渡态：保留上一次的会话数据
                state is VpnState.Connecting || state is VpnState.Disconnecting -> Unit
                else -> {
                    trafficMeter.reset()
                    connectedAtMs = null
                    _session.value = emptySession
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val PING_TIMEOUT_MS = 5000
        const val PING_START_TIMEOUT_MS = 6000L
    }
}
