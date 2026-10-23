package com.esrrhs.pingtunnel.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.esrrhs.pingtunnel.client.MainActivity
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.data.ConfigRepository
import com.esrrhs.pingtunnel.client.data.SettingsRepository
import com.esrrhs.pingtunnel.client.pingtunnel.PerAppMode
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelAuthException
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelException
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelProcess
import com.esrrhs.pingtunnel.client.pingtunnel.ValidationError
import com.esrrhs.pingtunnel.client.tun.HevConfig
import com.esrrhs.pingtunnel.client.tun.HevTunnel
import com.esrrhs.pingtunnel.client.util.CnRouteList
import com.esrrhs.pingtunnel.client.util.Formatters
import com.esrrhs.pingtunnel.client.util.SocksProbe
import com.esrrhs.pingtunnel.client.util.TrafficMeter
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

class PingtunnelVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化 connect / teardown / reconnect。 */
    private val lifecycleMutex = Mutex()

    /**
     * 与 [connectQueue] 一起保护 CONNECT/DISCONNECT 的状态切换。
     * 调用方只在这段临界区内改 [VpnStateHolder]，避免「tun 已消失、状态仍是 Disconnecting」时丢掉下一次 CONNECT。
     */
    private val lifecycleGate = Any()
    private val connectQueue = VpnConnectQueue()

    private var coreProcess: PingtunnelProcess? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var ruleServer: com.esrrhs.pingtunnel.client.proxy.RuleSocksServer? = null

    /** 每次成功建会话自增，用于让旧 spp 的意外退出回调失效。 */
    private var sessionGeneration = 0

    private lateinit var networkWatchdog: NetworkWatchdog
    private lateinit var trustedWifiMonitor: TrustedWifiMonitor

    /** 设置快照，供网络回调即时判断可信规则。 */
    @Volatile
    private var cachedSettings = com.esrrhs.pingtunnel.client.data.AppSettings()

    /** 本次会话（含重连）所属配置及统计。 */
    private var activeProfileId: String? = null
    private var baselineTx = 0L
    private var baselineRx = 0L
    private var sessionTxTotal = 0L
    private var sessionRxTotal = 0L

    private var reconnectJob: Job? = null
    private var notifRateJob: Job? = null
    private var latencyJob: Job? = null
    /** 最近一次隧道实测延迟（ms），-1 = 尚未测出/测量失败。仅通知读取。 */
    private val lastLatencyMs = java.util.concurrent.atomic.AtomicInteger(-1)
    private val rateMeter = TrafficMeter()
    private var currentProfileName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkWatchdog = NetworkWatchdog(
            context = applicationContext,
            onNetworkUpdate = { net ->
                updateUnderlyingNetwork(net)
            },
            onDefaultNetworkChanged = {
                val id = activeProfileId
                if (id != null && VpnStateHolder.state.value is VpnState.Connected) {
                    reconnectJob = scope.launch { recoverTunnel(id) }
                }
            },
        )
        trustedWifiMonitor = TrustedWifiMonitor(
            context = applicationContext,
            evaluate = { ssid ->
                val s = cachedSettings
                s.trustedWifiEnabled &&
                    com.esrrhs.pingtunnel.client.util.TrustedWifi.isTrusted(ssid, s.trustedWifiSsids)
            },
            onTrustedChanged = { trusted ->
                scope.launch {
                    if (trusted) pauseForTrustedWifi() else resumeFromTrustedWifi()
                }
            },
        )
        // 缓存全局设置供回调使用
        scope.launch {
            SettingsRepository(this@PingtunnelVpnService).settings.collect { cachedSettings = it }
        }
    }

    @Volatile
    private var overrideProfile: Profile? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                overrideProfile = null
                requestDisconnect()
            }
            // ACTION_CONNECT / null（系统重建 Service）
            else -> {
                val rawJson = intent?.getStringExtra(EXTRA_PROFILE_JSON)
                if (!rawJson.isNullOrBlank()) {
                    overrideProfile = runCatching {
                        profileJson.decodeFromString(Profile.serializer(), rawJson)
                    }.getOrNull()
                }
                requestConnect()
            }
        }
        return START_NOT_STICKY
    }

    /** 系统收回 VPN：停 hev / spp 并复位状态。收回后不再接上排队的 CONNECT。 */
    override fun onRevoke() {
        Log.i(TAG, "vpn revoked by system")
        reconnectJob?.cancel()
        synchronized(lifecycleGate) {
            connectQueue.clear()
            val current = VpnStateHolder.state.value
            if (current != VpnState.Disconnected && current != VpnState.Disconnecting) {
                VpnStateHolder.set(VpnState.Disconnecting)
            }
        }
        scope.launch { teardown(notifyDisconnected = true, honorQueue = false) }
    }

    override fun onDestroy() {
        stopRateNotificationUpdating()
        reconnectJob?.cancel()
        if (::networkWatchdog.isInitialized) networkWatchdog.stop()
        if (::trustedWifiMonitor.isInitialized) trustedWifiMonitor.stop()
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        coreProcess?.stop()
        coreProcess = null
        activeProfileId = null
        scope.cancel()
        super.onDestroy()
    }

    private fun requestConnect() {
        val command = synchronized(lifecycleGate) {
            val command = connectQueue.onConnect(VpnStateHolder.state.value)
            if (command == ConnectCommand.START) {
                VpnStateHolder.set(VpnState.Connecting)
            }
            command
        }
        when (command) {
            ConnectCommand.QUEUE ->
                Log.i(TAG, "connect queued until teardown finishes")
            ConnectCommand.IGNORE -> Unit
            ConnectCommand.START -> {
                startAsForeground(getString(R.string.notif_connecting))
                scope.launch { connect() }
            }
        }
    }

    private fun requestDisconnect() {
        val startTeardown = synchronized(lifecycleGate) {
            val start = connectQueue.onDisconnect(VpnStateHolder.state.value)
            if (start) VpnStateHolder.set(VpnState.Disconnecting)
            start
        }
        if (!startTeardown) return
        // 用户主动断开：取消可能进行中的自动重连
        reconnectJob?.cancel()
        startAsForeground(getString(R.string.notif_disconnecting))
        scope.launch { teardown(notifyDisconnected = true) }
    }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (VpnStateHolder.state.value !is VpnState.Connecting) {
                Log.i(TAG, "connect aborted, state=${VpnStateHolder.state.value}")
                return@withLock
            }
            try {
                val (repository, profile) = loadActiveProfile()
                sessionTxTotal = 0L
                sessionRxTotal = 0L
                establishSession(repository, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e.message}")
                stopDataPlaneAndCount()
                persistSessionTraffic()
                VpnStateHolder.set(
                    VpnState.Error(e.message ?: getString(R.string.error_connect_failed)),
                )
                finishService()
            }
        }
    }

    /**
     * 建立一次完整数据面（pingtunnel → 分流器 → TUN → hev）；成功后状态为 Connected。
     *
     * @param coreStartupTimeoutMs 等待 pingtunnel SOCKS 端口上线的超时：手动连接给足 15s，
     *        自动重连路径收紧到 6s，让故障服务器被快速跳过。
     */
    private suspend fun establishSession(
        repository: ConfigRepository,
        profile: Profile,
        coreStartupTimeoutMs: Long = CORE_STARTUP_TIMEOUT_MS,
    ) {
        profile.validate()?.let { throw PingtunnelException(validationText(it)) }
        // prepare() 在已授权（含 appops ACTIVATE_VPN）时把本包登记为当前 VPN，
        // 否则 establish() 会直接返回 null。界面路径会先调用它；adb / 磁贴直启服务时也要补上。
        if (VpnService.prepare(this) != null) {
            throw PingtunnelException(getString(R.string.error_vpn_consent))
        }
        // 无任何具备 INTERNET 能力的物理网络时快速失败，比干等内核 15s 超时体验好
        if (!hasInternetNetwork()) {
            throw PingtunnelException(getString(R.string.error_no_network))
        }
        val config = profile.config
        activeProfileId = profile.id

        // 1. 拉起上游：pingtunnel 子进程在本机 127.0.0.1 起 SOCKS5（CONNECT + UDP ASSOCIATE）。
        //    注意端口上线只代表进程就绪，隧道真实可用性由 SocksProbe 端到端探测负责。
        val generation = ++sessionGeneration
        val core = PingtunnelProcess(this)
        core.onUnexpectedExit = {
            if (generation == sessionGeneration &&
                VpnStateHolder.state.value is VpnState.Connected &&
                activeProfileId == profile.id
            ) {
                reconnectJob = scope.launch { recoverTunnel(profile.id) }
            }
        }
        val socksPort = core.start(config, coreStartupTimeoutMs)
        Log.i(TAG, "pingtunnel socks5 listening on 127.0.0.1:$socksPort")
        coreProcess = core

        // 1b. 用户态分流器：TUN 全量抓包后，由它决定每一条连接本地直连还是转发上游。
        //     以下任一条件成立就必须经过分流器：
        //       - 启用域名直连规则（内置大陆域名表 + 用户自定义）；
        //       - bypass LAN（私有/CGNAT 段直连）；
        //       - bypass CN（精确 chnroute，零块扩展）。
        //     纯全局代理模式保留 hev → pingtunnel 直连路径，减少一跳转发开销。
        val useRuleServer = cachedSettings.domainDirectEnabled ||
            profile.bypassLan ||
            profile.bypassCn
        val directRules: Set<String> = if (cachedSettings.domainDirectEnabled) {
            LinkedHashSet<String>(
                com.esrrhs.pingtunnel.client.util.BundledDirectDomains.load(this),
            ).apply {
                addAll(
                    com.esrrhs.pingtunnel.client.util.DomainRuleMatcher
                        .parse(cachedSettings.domainDirectRulesText),
                )
            }
        } else {
            emptySet()
        }
        val directCidrsV4 = if (profile.bypassCn) CnRouteList.loadV4(this) else emptyList()
        val directCidrsV6 = if (profile.bypassCn) CnRouteList.loadV6(this) else emptyList()

        // 与分流器同输入的判定器：连接采集器据此标记「直连」，保证展示即真实路径。
        // 纯全局 SPP 模式下所有输入为空，等价于 DirectClassifier.EMPTY。
        val directClassifier = com.esrrhs.pingtunnel.client.util.DirectClassifier(
            directDomains = directRules,
            directIpv4Cidrs = directCidrsV4,
            directIpv6Cidrs = directCidrsV6,
            bypassPrivate = profile.bypassLan,
        )

        val hevSocksPort = if (useRuleServer) {
            if (cachedSettings.domainDirectEnabled) {
                Log.i(TAG, "domain direct rules: ${directRules.size} domains")
            }
            if (profile.bypassCn) {
                Log.i(TAG, "cn split: ${directCidrsV4.size} v4 + ${directCidrsV6.size} v6 exact CIDRs")
            }
            val upstream = com.esrrhs.pingtunnel.client.proxy.SocksUpstream("127.0.0.1", socksPort)
            val server = com.esrrhs.pingtunnel.client.proxy.RuleSocksServer(
                upstream = upstream,
                directDomains = directRules,
                directIpv4Cidrs = directCidrsV4,
                directIpv6Cidrs = directCidrsV6,
                bypassPrivate = profile.bypassLan,
            )
            server.start()
            ruleServer = server
            server.port ?: throw PingtunnelException(getString(R.string.error_rule_proxy))
        } else {
            socksPort
        }
        ActiveSession.socksPort = hevSocksPort
        // 2. 建立 TUN（分应用规则；路由始终全抓，CN/LAN 分流在用户态分流器完成）
        val tun = buildTun(profile)
            .establish() ?: throw PingtunnelException(getString(R.string.error_establish))
        Log.i(TAG, "tun established")
        tunInterface = tun

        // 3. hev：getFd() 只传 int，PFD 所有权保留在本类
        val configFile = writeHevConfig(hevSocksPort, profile.config.enableIpv6)
        com.esrrhs.pingtunnel.client.util.RuntimeLogs.trim(File(filesDir, "hev.log"))
        val hevStarted = try {
            HevTunnel.start(configFile.absolutePath, tun.fd)
        } catch (e: IllegalStateException) {
            throw PingtunnelException(getString(R.string.error_hev_missing))
        }
        if (!hevStarted) {
            throw PingtunnelException(getString(R.string.error_hev_start))
        }
        Log.i(TAG, "hev tunnel started")

        HevTunnel.stats()?.let { s ->
            baselineTx = s.getOrNull(1) ?: 0L
            baselineRx = s.getOrNull(3) ?: 0L
        }

        updateNotification(getString(R.string.notif_connected, profile.name))
        startRateNotificationUpdating(profile.name)
        startLatencyUpdating()
        VpnStateHolder.set(VpnState.Connected)

        // 全局连接采集（当前连接页 + 单连接历史），与界面是否打开无关
        com.esrrhs.pingtunnel.client.util.ConnectionRecorder.start(
            scope = scope,
            context = this,
            profile = profile,
            direct = directClassifier,
        )

        // 4. 监视默认网络切换（WiFi↔蜂窝），切换后主动重建数据面
        networkWatchdog.start()
        updateUnderlyingNetwork(networkWatchdog.current)
        // 可信 WiFi：本次建链的网络作为基线，之后翻转才暂停/恢复
        trustedWifiMonitor.start()
    }

    /** 接入可信 WiFi：结账数据面并进入暂停态（Service 保留，监听离开后恢复）。 */
    private suspend fun pauseForTrustedWifi() {
        lifecycleMutex.withLock {
            if (VpnStateHolder.state.value !is VpnState.Connected) return
            sessionGeneration++
            stopDataPlaneAndCount()
            persistSessionTraffic()
            VpnStateHolder.set(VpnState.Paused)
            updateNotification(getString(R.string.notif_paused_trusted))
        }
    }

    /** 离开可信网络：从暂停态自动重连。 */
    private fun resumeFromTrustedWifi() {
        if (VpnStateHolder.state.value !is VpnState.Paused) return
        startAsForeground(getString(R.string.notif_connecting))
        VpnStateHolder.set(VpnState.Connecting)
        scope.launch { connect() }
    }

    /**
     * 数据面丢失恢复：spp 意外退出或默认网络切换共用。
     *
     * 自动重连**无次数上限**，退避 1s/2s/4s/5s/5s…，直到成功或被用户取消；
     * 开启故障切换时，每个配置连续失败 [FAILOVER_CYCLE] 次后轮换到下一个候选。
     */
    private suspend fun recoverTunnel(profileId: String) {
        lifecycleMutex.withLock {
            // 可能在等锁期间已被用户断开 / 已被其他恢复流程处理
            if (VpnStateHolder.state.value !is VpnState.Connected) return
            if (activeProfileId != profileId) return

            val settings = runCatching {
                SettingsRepository(this@PingtunnelVpnService).settings.first()
            }.getOrNull()

            // 主动停旧数据面（网络切换）前作废旧 spp 的退出回调，避免重复恢复
            sessionGeneration++
            stopDataPlaneAndCount()

            if (settings?.autoReconnect != true) {
                persistSessionTraffic()
                VpnStateHolder.set(
                    VpnState.Error(getString(R.string.error_tunnel_lost)),
                )
                finishService()
                return
            }

            // 候选：当前配置优先；开启故障切换时追加按延迟排序的全部备用配置
            val candidates = mutableListOf(profileId)
            if (settings.failover) {
                val profiles = ConfigRepository(this).profiles.first()
                Failover.orderedOthers(profiles, profileId).forEach { candidates.add(it.id) }
            }

            VpnStateHolder.set(VpnState.Connecting)
            updateNotification(getString(R.string.notif_reconnecting))

            var attempt = 1
            var index = 0
            while (true) {
                // 无网时反复拉起 spp 只会拿到超时报错并推迟真正的建链时机：
                // 挂起等网络，物理网络恢复的瞬间立即建链；最长等 30s 兜底放行。
                awaitInternetNetwork()
                delay(ReconnectBackoff.delayMs(attempt))
                val candidateId = candidates[index]
                var authFailed = false
                try {
                    val (repository, profile) = loadActiveProfile()
                    // 候选在重连期间被删除：从轮换列表移除
                    if (profile.id != candidateId) {
                        candidates.removeAt(index)
                        if (candidates.isEmpty()) {
                            persistSessionTraffic()
                            VpnStateHolder.set(VpnState.Error(getString(R.string.error_no_profile)))
                            finishService()
                            return
                        }
                        index %= candidates.size
                        continue
                    }
                    establishSession(repository, profile, RECONNECT_SPP_TIMEOUT_MS)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PingtunnelAuthException) {
                    // key/encrypt 不匹配是确定性错误：同一候选重试无意义，立即轮换
                    authFailed = true
                    Log.w(TAG, "reconnect candidate=$candidateId rejected by server auth")
                    stopDataPlaneAndCount()
                } catch (e: Exception) {
                    Log.w(TAG, "reconnect attempt $attempt (candidate=$candidateId) failed: ${e.message}")
                    stopDataPlaneAndCount()
                }

                if (authFailed) {
                    candidates.removeAt(index)
                    if (candidates.isEmpty()) {
                        persistSessionTraffic()
                        VpnStateHolder.set(VpnState.Error(getString(R.string.error_auth_failed)))
                        finishService()
                        return
                    }
                    index %= candidates.size
                    attempt = 1
                    ConfigRepository(this).setActive(candidates[index])
                    Log.i(TAG, "auth failure, failover to profile ${candidates[index]}")
                    updateNotification(getString(R.string.notif_failover))
                    continue
                }

                attempt++
                // 当前候选连续失败一个周期：轮换到下一个配置（环状）
                if (candidates.size > 1 && (attempt - 1) % FAILOVER_CYCLE == 0) {
                    val next = (index + 1) % candidates.size
                    if (next != index) {
                        index = next
                        attempt = 1
                        ConfigRepository(this).setActive(candidates[index])
                        Log.i(TAG, "failover to profile ${candidates[index]}")
                        updateNotification(getString(R.string.notif_failover))
                    }
                }
            }
        }
    }

    private suspend fun teardown(
        errorMessage: String? = null,
        notifyDisconnected: Boolean = false,
        honorQueue: Boolean = true,
    ) {
        val followUp = lifecycleMutex.withLock {
            trustedWifiMonitor.stop()
            stopDataPlaneAndCount()
            persistSessionTraffic()
            val follow = synchronized(lifecycleGate) {
                val follow = if (honorQueue) {
                    connectQueue.takeFollowUp(failed = errorMessage != null)
                } else {
                    connectQueue.clear()
                    false
                }
                if (follow) {
                    VpnStateHolder.set(VpnState.Connecting)
                } else {
                    when {
                        errorMessage != null -> VpnStateHolder.set(VpnState.Error(errorMessage))
                        notifyDisconnected &&
                            VpnStateHolder.state.value !is VpnState.Error ->
                            VpnStateHolder.set(VpnState.Disconnected)
                    }
                }
                follow
            }
            if (!follow) finishService()
            follow
        }
        if (followUp) {
            Log.i(TAG, "connect resumed after teardown")
            startAsForeground(getString(R.string.notif_connecting))
            connect()
        }
    }

    /** 停数据面并把本段字节计入会话累计。 */
    private suspend fun stopDataPlaneAndCount() {
        stopRateNotificationUpdating()
        // 先收尾全局连接采集（残留会话落历史库），再停数据面
        runCatching {
            com.esrrhs.pingtunnel.client.util.ConnectionRecorder.stop(this@PingtunnelVpnService)
        }
        if (::networkWatchdog.isInitialized) networkWatchdog.stop()
        val finalStats = HevTunnel.stats()
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        tunInterface = null
        ruleServer?.stop()
        ruleServer = null
        coreProcess?.stop()
        coreProcess = null
        ActiveSession.socksPort = null
        if (finalStats != null) {
            val segTx = ((finalStats.getOrNull(1) ?: 0L) - baselineTx).coerceAtLeast(0)
            val segRx = ((finalStats.getOrNull(3)  ?: 0L) - baselineRx).coerceAtLeast(0)
            sessionTxTotal += segTx
            sessionRxTotal += segRx
        }
        baselineTx = 0L
        baselineRx = 0L
    }

    private suspend fun persistSessionTraffic() {
        val pid = activeProfileId
        if (pid != null && (sessionTxTotal > 0 || sessionRxTotal > 0)) {
            runCatching {
                ConfigRepository(this).addTraffic(pid, sessionTxTotal, sessionRxTotal)
            }
        }
        sessionTxTotal = 0L
        sessionRxTotal = 0L
        activeProfileId = null
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 读取当前选中配置；不存在有效配置时抛错。 */
    private suspend fun loadActiveProfile(): Pair<ConfigRepository, Profile> {
        val repository = ConfigRepository(this)
        val profile = overrideProfile ?: run {
            val profiles = repository.profiles.first()
            val activeId = repository.activeId.first()
            profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        }
        return if (profile == null) {
            throw PingtunnelException(getString(R.string.error_no_profile))
        } else {
            repository to profile
        }
    }

    private fun buildTun(profile: Profile): Builder {
        val plan = TunPlan.of(profile.config.enableIpv6)
        val builder = Builder()
            .setSession(plan.session)
            .setMtu(plan.mtu)
        plan.addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        plan.dnsServers.forEach { builder.addDnsServer(it) }
        plan.routes.forEach { builder.addRoute(it.address, it.prefixLength) }

        applyPerApp(builder, profile)
        return builder
    }

    /** 分应用代理规则；已卸载的包忽略。自身始终在黑名单，防本进程流量回环进 TUN。 */
    private fun applyPerApp(builder: Builder, profile: Profile) {
        val routing = PerAppRoutingResolver.resolve(
            profile.perAppMode,
            profile.perAppPackages,
            packageName,
        )
        routing.disallowedPackages.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }
        routing.allowedPackages.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }
        }
    }

    private fun writeHevConfig(socksPort: Int, enableIpv6: Boolean): File {
        val yaml = HevConfig.render(
            socksPort = socksPort,
            enableIpv6 = enableIpv6,
            logFilePath = File(filesDir, "hev.log").absolutePath,
        )
        return File(filesDir, "hev.yml").apply { writeText(yaml) }
    }

    private fun validationText(error: ValidationError): String =
        com.esrrhs.pingtunnel.client.util.ValidationMessages.text(this, error)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground(text: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startRateNotificationUpdating(profileName: String) {
        currentProfileName = profileName
        notifRateJob?.cancel()
        rateMeter.reset()
        HevTunnel.stats()?.let { s ->
            val tx = s.getOrNull(1) ?: 0L
            val rx = s.getOrNull(3) ?: 0L
            rateMeter.rebaseline(tx, rx, System.currentTimeMillis())
        }
        notifRateJob = scope.launch {
            while (true) {
                delay(RATE_NOTIF_INTERVAL_MS)
                if (VpnStateHolder.state.value !is VpnState.Connected) break
                val raw = HevTunnel.stats() ?: continue
                val now = System.currentTimeMillis()
                val tx = raw.getOrNull(1) ?: 0L
                val rx = raw.getOrNull(3) ?: 0L
                val rates = rateMeter.update(tx, rx, now)
                val txStr = Formatters.formatRate(rates.txBytesPerSec)
                val rxStr = Formatters.formatRate(rates.rxBytesPerSec)
                val ping = lastLatencyMs.get()
                val text = if (ping >= 0) {
                    getString(
                        R.string.notif_connected_with_rate_ping,
                        currentProfileName.orEmpty(),
                        txStr,
                        rxStr,
                        getString(R.string.ping_ms, ping),
                    )
                } else {
                    getString(
                        R.string.notif_connected_with_rate,
                        currentProfileName.orEmpty(),
                        txStr,
                        rxStr,
                    )
                }
                updateNotification(text)
            }
        }
    }

    /**
     * 周期性实测隧道延迟并显示在通知里。
     *
     * 走 `SocksProbe` 的真实端到端测量（SOCKS 建连 + TLS 握手 + HTTP），不是 ping；
     * 每次耗费一次短连接，因此间隔取分钟级，且只在 Connected 期间进行。
     * 测量失败保持上一次结果消失（-1），通知回退为不带延迟的文本。
     */
    private fun startLatencyUpdating() {
        latencyJob?.cancel()
        lastLatencyMs.set(-1)
        latencyJob = scope.launch(Dispatchers.IO) {
            delay(LATENCY_FIRST_DELAY_MS)
            while (true) {
                if (VpnStateHolder.state.value !is VpnState.Connected) break
                val port = ActiveSession.socksPort
                if (port != null) {
                    val ms = SocksProbe.measure(port, samples = 1, timeoutMs = LATENCY_TIMEOUT_MS)
                    lastLatencyMs.set(ms)
                }
                delay(LATENCY_INTERVAL_MS)
            }
        }
    }

    private fun stopRateNotificationUpdating() {
        notifRateJob?.cancel()
        notifRateJob = null
        latencyJob?.cancel()
        latencyJob = null
        lastLatencyMs.set(-1)
        currentProfileName = null
        rateMeter.reset()
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pingtunnel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    /**
     * 把 VPN 出口绑到当前物理网络。
     *
     * [network] 为 null 时必须传 null，让系统改走默认网络。
     * 不能回退到 activeNetwork：VPN 进程里那往往是 VPN 自己，
     * 或者是刚刚拆掉的 Wi-Fi，再写回去会把分流流量钉死。
     */
    private fun updateUnderlyingNetwork(network: Network?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val networks = if (network != null) arrayOf(network) else null
            runCatching {
                setUnderlyingNetworks(networks)
                Log.i(TAG, "setUnderlyingNetworks: ${network ?: "default"}")
            }.onFailure {
                Log.w(TAG, "setUnderlyingNetworks failed", it)
            }
        }
    }

    /** 当前是否存在带 INTERNET 能力的物理（非 VPN）网络。 */
    private fun hasInternetNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    /**
     * 重连门控：物理网络不可用时挂起等待，网络一恢复立即放行（比固定轮询恢复更快）。
     *
     * 只要求声明了 INTERNET 能力、不要求 VALIDATED——受限网络里直连探测不通但
     * SPP 服务器可达的场景必须允许建链；[NETWORK_GATE_MAX_WAIT_MS] 后兜底放行一次。
     */
    private suspend fun awaitInternetNetwork() {
        if (hasInternetNetwork()) return
        Log.i(TAG, "reconnect gated: no physical network with INTERNET capability")
        withTimeoutOrNull(NETWORK_GATE_MAX_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val cm = getSystemService(ConnectivityManager::class.java)
                if (cm == null) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (cont.isActive &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        ) {
                            Log.i(TAG, "reconnect gate released: physical network available")
                            cont.resume(Unit)
                        }
                    }
                }
                runCatching { cm.registerDefaultNetworkCallback(callback) }
                    .onFailure { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.esrrhs.pingtunnel.client.action.CONNECT"
        const val ACTION_DISCONNECT = "com.esrrhs.pingtunnel.client.action.DISCONNECT"
        const val EXTRA_PROFILE_JSON = "com.esrrhs.pingtunnel.client.extra.PROFILE_JSON"
        private val profileJson = Json { ignoreUnknownKeys = true }

        private const val CHANNEL_ID = "pingtunnel_vpn"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PingtunnelVpnService"

        /** 每个配置连续失败多少次后轮换到下一个候选。 */
        private const val FAILOVER_CYCLE = 2
        private const val RATE_NOTIF_INTERVAL_MS = 1000L
        /** 延迟实测间隔：每次都是真实 TLS 握手 + HTTP，分钟级足够，别做成秒级。 */
        private const val LATENCY_INTERVAL_MS = 60_000L
        /** 首次测量延后一点：刚建链时数据面还在预热，测出来偏高没意义。 */
        private const val LATENCY_FIRST_DELAY_MS = 5_000L
        private const val LATENCY_TIMEOUT_MS = 8000

        /** 手动连接时等待 spp SOCKS 端口上线的超时（服务器不可达时的报错上限）。 */
        private const val CORE_STARTUP_TIMEOUT_MS = 15_000L
        /** 自动重连时收紧到 6s：故障服务器被快速跳过，尽快轮到备用配置。 */
        private const val RECONNECT_SPP_TIMEOUT_MS = 6_000L
        /** 无物理网络时重连门控的最长等待，超时后兜底尝试一次。 */
        private const val NETWORK_GATE_MAX_WAIT_MS = 30_000L
    }
}
