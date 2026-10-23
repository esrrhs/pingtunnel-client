@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.esrrhs.pingtunnel.client.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.data.AppSettings
import com.esrrhs.pingtunnel.client.util.WifiNames
import com.esrrhs.pingtunnel.client.vpn.AlwaysOnVpn

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    // 系统 BACK 回到主界面，与顶栏返回一致
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ToggleRow(
                title = stringResource(R.string.setting_boot_title),
                subtitle = stringResource(R.string.setting_boot_subtitle),
                checked = settings.bootStart,
                onCheckedChange = { onChange(settings.copy(bootStart = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.setting_reconnect_title),
                subtitle = stringResource(R.string.setting_reconnect_subtitle),
                checked = settings.autoReconnect,
                onCheckedChange = { onChange(settings.copy(autoReconnect = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.setting_failover_title),
                subtitle = stringResource(R.string.setting_failover_subtitle),
                checked = settings.failover,
                onCheckedChange = { onChange(settings.copy(failover = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.setting_bypass_lan_title),
                subtitle = stringResource(R.string.setting_bypass_lan_subtitle),
                checked = settings.defaultBypassLan,
                onCheckedChange = { onChange(settings.copy(defaultBypassLan = it)) },
            )

            DomainDirectSection(settings = settings, onChange = onChange)
            TrustedWifiSection(settings = settings, onChange = onChange)
            AlwaysOnCard()
            PrivacyPolicyRow()
        }
    }
}

@Composable
private fun PrivacyPolicyRow() {
    val context = LocalContext.current
    val url = stringResource(R.string.privacy_policy_url)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.privacy_policy_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.privacy_policy_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ) {
            Text(stringResource(R.string.privacy_policy_title))
        }
    }
}

@Composable
private fun DomainDirectSection(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    ToggleRow(
        title = stringResource(R.string.setting_domain_direct_title),
        subtitle = stringResource(R.string.setting_domain_direct_subtitle),
        checked = settings.domainDirectEnabled,
        onCheckedChange = { onChange(settings.copy(domainDirectEnabled = it)) },
    )
    if (settings.domainDirectEnabled) {
        OutlinedTextField(
            value = settings.domainDirectRulesText,
            onValueChange = { onChange(settings.copy(domainDirectRulesText = it)) },
            label = { Text(stringResource(R.string.domain_rules_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            minLines = 4,
            maxLines = 8,
        )
    }
}

@Composable
private fun TrustedWifiSection(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    val context = LocalContext.current
    var newSsid by remember { mutableStateOf("") }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            addCurrentWifi(context, settings, onChange)
        } else {
            Toast
                .makeText(context, context.getString(R.string.toast_location_required), Toast.LENGTH_LONG)
                .show()
        }
    }

    ToggleRow(
        title = stringResource(R.string.setting_trusted_title),
        subtitle = stringResource(R.string.setting_trusted_subtitle),
        checked = settings.trustedWifiEnabled,
        onCheckedChange = { onChange(settings.copy(trustedWifiEnabled = it)) },
    )

    if (settings.trustedWifiEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newSsid,
                onValueChange = { newSsid = it },
                label = { Text(stringResource(R.string.ssid_field_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val name = newSsid.trim()
                if (name.isNotEmpty()) {
                    onChange(settings.copy(trustedWifiSsids = settings.trustedWifiSsids + name))
                    newSsid = ""
                }
            }) { Text(stringResource(R.string.action_add)) }
        }

        OutlinedButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    addCurrentWifi(context, settings, onChange)
                } else {
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(stringResource(R.string.action_add_current_wifi)) }

        if (settings.trustedWifiSsids.isEmpty()) {
            Text(
                text = stringResource(R.string.trusted_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                settings.trustedWifiSsids.forEach { ssid ->
                    AssistChip(
                        onClick = {
                            onChange(settings.copy(trustedWifiSsids = settings.trustedWifiSsids - ssid))
                        },
                        label = { Text(stringResource(R.string.trusted_chip_remove, ssid)) },
                    )
                }
            }
        }
    }
}

private fun addCurrentWifi(
    context: Context,
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    val ssid = WifiNames.currentSsid(context)
    if (ssid == null) {
        Toast
            .makeText(context, context.getString(R.string.toast_wifi_unavailable), Toast.LENGTH_LONG)
            .show()
    } else {
        onChange(settings.copy(trustedWifiSsids = settings.trustedWifiSsids + ssid))
        Toast
            .makeText(context, context.getString(R.string.toast_wifi_added, ssid), Toast.LENGTH_SHORT)
            .show()
    }
}

/**
 * Always-on VPN 只能由用户在系统设置中开启，App 无权自行切换；
 * 这里给出步骤说明并提供直达入口（部分 ROM 无 VPN 设置页时回退到无线设置）。
 */
@Composable
private fun AlwaysOnCard() {
    val context = LocalContext.current
    // 从系统设置返回后要重读（用户可能刚开/关），用 ON_RESUME 触发刷新而不是只算一次
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val read = {
            enabled = AlwaysOnVpn.isEnabledFor(
                AlwaysOnVpn.configuredApp(context.contentResolver),
                context.packageName,
            )
        }
        read()
        val owner = view.findViewTreeLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) read()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.alwayson_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        enabled?.let { on ->
            Text(
                text = stringResource(
                    if (on) R.string.alwayson_state_on else R.string.alwayson_state_off,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.alwayson_steps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openSystemVpnSettings(context) }) {
            Text(stringResource(R.string.action_open_vpn_settings))
        }
    }
}

private fun openSystemVpnSettings(context: Context) {
    val intent = Intent("android.net.vpn.SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }.onFailure {
            Toast
                .makeText(context, context.getString(R.string.toast_vpn_settings_unavailable), Toast.LENGTH_LONG)
                .show()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
