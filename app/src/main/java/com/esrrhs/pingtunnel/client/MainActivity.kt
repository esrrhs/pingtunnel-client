package com.esrrhs.pingtunnel.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.ui.AppPickerScreen
import com.esrrhs.pingtunnel.client.ui.ConnectionsScreen
import com.esrrhs.pingtunnel.client.ui.HistoryScreen
import com.esrrhs.pingtunnel.client.ui.IpQueryDialog
import com.esrrhs.pingtunnel.client.ui.LeakScreen
import com.esrrhs.pingtunnel.client.ui.LogsScreen
import androidx.lifecycle.lifecycleScope
import com.esrrhs.pingtunnel.client.ui.MainScreen
import com.esrrhs.pingtunnel.client.ui.MainViewModel
import com.esrrhs.pingtunnel.client.ui.ProfileEditorScreen
import com.esrrhs.pingtunnel.client.ui.SettingsScreen
import com.esrrhs.pingtunnel.client.ui.StatsScreen
import com.esrrhs.pingtunnel.client.ui.theme.PingtunnelClientTheme
import com.esrrhs.pingtunnel.client.util.ProfileShare
import com.esrrhs.pingtunnel.client.util.ProfilesFile
import com.esrrhs.pingtunnel.client.util.ValidationMessages
import com.esrrhs.pingtunnel.client.vpn.VpnController
import com.esrrhs.pingtunnel.client.vpn.VpnState
import com.esrrhs.pingtunnel.client.vpn.VpnStateHolder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingConnectProfileId: String? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService()
            } else {
                toast(getString(R.string.toast_vpn_permission_denied))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpnPermissionAndStart()
        }

    /** 扫码（zxing-android-embedded 自带扫码界面与相机权限处理）。 */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        val profile = ProfileShare.decode(contents)
        val error = profile?.validate()
        when {
            profile == null -> toast(getString(R.string.toast_invalid_qr))
            error != null ->
                toast(com.esrrhs.pingtunnel.client.util.ValidationMessages.text(this, error))
            else -> {
                viewModel.importScanned(profile)
                toast(getString(R.string.toast_imported, profile.name))
            }
        }
    }

    /** 导出全部配置到用户选择的文件。 */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = ProfilesFile.encode(viewModel.profiles.value)
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                toast(getString(R.string.toast_exported, viewModel.profiles.value.size))
            } catch (e: Exception) {
                toast(getString(R.string.toast_export_failed, e.message ?: ""))
            }
        }

    /** 从文件导入配置。 */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                } ?: return@registerForActivityResult
                val list = ProfilesFile.decode(text)
                if (list.isEmpty()) {
                    toast(getString(R.string.toast_import_empty))
                } else {
                    lifecycleScope.launch {
                        // 仓库层会丢弃损坏/篡改条目；按实际导入数给出反馈
                        val imported = viewModel.importProfiles(list)
                        toast(
                            when {
                                imported == 0 -> getString(R.string.toast_import_empty)
                                imported == list.size ->
                                    getString(R.string.toast_imported_count, imported)
                                else -> getString(
                                    R.string.toast_import_partial,
                                    imported, list.size - imported,
                                )
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.toast_import_failed, e.message ?: ""))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 仅首次创建处理启动 Intent：旋转/进程恢复导致的重建会带着同一个 Intent
        // 再走这里——深度链接会重复导入（撞 id 后仓库会换新 UUID 产生重复配置），
        // 磁贴 EXTRA_CONNECT 在已连接时重建甚至会误触发断开。
        if (savedInstanceState == null) {
            // 来自 Quick Settings 磁贴：VPN 尚未授权时先打开本页，授权后立即连接
            if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) {
                lifecycleScope.launch {
                    val id = viewModel.awaitActiveProfileId()
                    if (id != null) {
                        prepareConnectOrDisconnect(id)
                    } else {
                        toast(getString(R.string.toast_add_profile_first))
                    }
                }
            }

            // pingtunnel:// 深度链接冷启动导入
            handleProfileLink(intent)
        }

        setContent {
            PingtunnelClientTheme {
                var screen by rememberSaveable { mutableStateOf("list") }
                // 配置弹窗开关提升到 Activity：进入统计/编辑等页再返回时弹窗仍在
                var sheetOpen by rememberSaveable { mutableStateOf(false) }
                // 编辑中的草稿提升到 Activity，供 App 勾选器返回后保留
                var draft by remember { mutableStateOf(Profile()) }
                var editorIsNew by rememberSaveable { mutableStateOf(true) }
                var qrId by remember { mutableStateOf<String?>(null) }

                val profiles by viewModel.profiles.collectAsState()
                val activeId by viewModel.activeId.collectAsState()
                val vpnState by VpnStateHolder.state.collectAsState()
                val session by viewModel.session.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val testingPingId by viewModel.testingPingId.collectAsState()
                val selfCheckState by viewModel.selfCheck.collectAsState()
                val leakState by viewModel.leak.collectAsState()
                val connections by com.esrrhs.pingtunnel.client.util.ConnectionRecorder
                    .liveGroups.collectAsState()
                val connectionLogs by viewModel.connectionLogs.collectAsState()
                val ipQueryState by viewModel.ipQuery.collectAsState()

                when (screen) {
                    "logs" -> LogsScreen(onBack = { screen = "list" })

                    "history" -> HistoryScreen(
                        entries = connectionLogs,
                        onBack = { screen = "list" },
                        onClear = viewModel::clearConnectionLogs,
                        onDelete = viewModel::deleteConnectionLog,
                    )

                    "connections" -> ConnectionsScreen(
                        groups = connections,
                        vpnState = vpnState,
                        activeProfile = profiles.firstOrNull { it.id == activeId },
                        onBack = { screen = "list" },
                    )

                    "leak" -> LeakScreen(
                        state = leakState,
                        connected = vpnState is VpnState.Connected,
                        onRun = viewModel::runLeakCheck,
                        onBack = { screen = "list" },
                    )

                    "stats" -> StatsScreen(
                        profiles = profiles,
                        activeId = activeId,
                        vpnState = vpnState,
                        session = session,
                        onResetTraffic = viewModel::resetTraffic,
                        onResetProfileTraffic = viewModel::resetTrafficFor,
                        onBack = { screen = "list" },
                    )

                    "settings" -> SettingsScreen(
                        settings = settings,
                        onChange = viewModel::updateSettings,
                        onBack = { screen = "list" },
                    )

                    "apppicker" -> AppPickerScreen(
                        initialSelected = draft.perAppPackages.toSet(),
                        onConfirm = { packages ->
                            draft = draft.copy(perAppPackages = packages.toList())
                            screen = "editor"
                        },
                        onBack = { screen = "editor" },
                    )

                    "editor" -> ProfileEditorScreen(
                        initial = draft,
                        isNew = editorIsNew,
                        onSave = { profile, callback ->
                            viewModel.saveProfile(profile) { error ->
                                if (error != null) {
                                    toast(ValidationMessages.text(this@MainActivity, error))
                                }
                                callback(error)
                                if (error == null) screen = "list"
                            }
                        },
                        onPickApps = {
                            screen = "apppicker"
                        },
                        onBack = { screen = "list" },
                    )

                    else -> MainScreen(
                        profiles = profiles,
                        activeId = activeId,
                        vpnState = vpnState,
                        session = session,
                        testingPingId = testingPingId,
                        qrProfile = profiles.firstOrNull { it.id == qrId },
                        sheetOpen = sheetOpen,
                        onSheetOpenChange = { sheetOpen = it },
                        onProfileClick = ::prepareConnectOrDisconnect,
                        onSelectProfile = viewModel::selectProfile,
                        onEdit = { id ->
                            profiles.firstOrNull { it.id == id }?.let {
                                draft = it
                                editorIsNew = false
                                screen = "editor"
                            }
                        },
                        onDelete = viewModel::deleteProfile,
                        onAdd = {
                            draft = Profile()
                            editorIsNew = true
                            screen = "editor"
                        },
                        onShowLogs = { screen = "logs" },
                        onSettings = { screen = "settings" },
                        onScan = ::startScan,
                        onImport = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onExport = { exportLauncher.launch("pingtunnel-profiles.json") },
                        onTestPing = { id ->
                            viewModel.testPing(id) { ms ->
                                if (ms < 0) toast(getString(R.string.toast_ping_failed))
                            }
                        },
                        onShowStats = { screen = "stats" },
                        onShowConnections = { screen = "connections" },
                        onShowIp = viewModel::runIpQuery,
                        onShowHistory = { screen = "history" },
                        onShowLeak = { screen = "leak" },
                        onRunCheck = viewModel::runSelfCheck,
                        onDismissCheck = viewModel::dismissSelfCheck,
                        selfCheckState = selfCheckState,
                        onShowQr = { qrId = it },
                        onDismissQr = { qrId = null },
                    )
                }

                IpQueryDialog(
                    state = ipQueryState,
                    connected = vpnState is VpnState.Connected,
                    onRefresh = viewModel::runIpQuery,
                    onDismiss = viewModel::dismissIpQuery,
                )
            }
        }
    }

    private fun startScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    /** 列表点击：正在使用的配置 → 断开；其他配置 → 校验权限后连接。 */
    private fun prepareConnectOrDisconnect(id: String) {
        val current = VpnStateHolder.state.value
        if (id == viewModel.activeId.value &&
            (current is VpnState.Connected || current is VpnState.Connecting)
        ) {
            VpnController.disconnect(this)
            return
        }
        pendingConnectProfileId = id
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestVpnPermissionAndStart()
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val id = pendingConnectProfileId ?: return
        viewModel.onProfileClicked(id)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProfileLink(intent)
    }

    /** 处理 pingtunnel:// 配置链接（网页/分享/剪贴板），与扫码共用同一编解码。 */
    private fun handleProfileLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val text = intent.dataString ?: return
        val profile = ProfileShare.decode(text)
        val error = profile?.validate()
        when {
            profile == null -> toast(getString(R.string.toast_bad_link))
            error != null ->
                toast(com.esrrhs.pingtunnel.client.util.ValidationMessages.text(this, error))
            else -> {
                viewModel.importScanned(profile)
                toast(getString(R.string.toast_imported, profile.name))
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** 由 Quick Settings 磁贴在需要 VPN 授权时携带：进页面后自动发起连接。 */
        const val EXTRA_CONNECT = "com.esrrhs.pingtunnel.client.extra.CONNECT"
    }
}
