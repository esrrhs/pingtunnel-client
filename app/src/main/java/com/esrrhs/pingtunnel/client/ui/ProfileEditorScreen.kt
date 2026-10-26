package com.esrrhs.pingtunnel.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.pingtunnel.PerAppMode
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelConfig
import com.esrrhs.pingtunnel.client.pingtunnel.ValidationError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    initial: Profile,
    isNew: Boolean,
    onSave: (Profile, (ValidationError?) -> Unit) -> Unit,
    onPickApps: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    // 系统 BACK 放弃编辑回主界面，与顶栏返回一致
    BackHandler(onBack = onBack)
    var profile by remember { mutableStateOf(initial) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.editor_add_title else R.string.editor_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(profile) { error -> if (error == null) onBack() } }) {
                        Text(stringResource(R.string.action_save))
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = profile.name,
                onValueChange = { profile = profile.copy(name = it) },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PerAppSection(
                profile = profile,
                onChange = { profile = it },
                onPickApps = onPickApps,
            )

            SmartSplitSection(
                profile = profile,
                onChange = { profile = it },
            )

            ConnectionFields(
                config = profile.config,
                onChange = { c -> profile = profile.copy(config = c) },
            )
        }
    }
}

@Composable
private fun PerAppSection(
    profile: Profile,
    onChange: (Profile) -> Unit,
    onPickApps: (Set<String>) -> Unit,
) {
    Text(text = stringResource(R.string.section_per_app), style = typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PerAppMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.perAppMode == mode,
                onClick = { onChange(profile.copy(perAppMode = mode)) },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                PerAppMode.ALL -> R.string.per_app_all
                                PerAppMode.ALLOWED -> R.string.per_app_allowed
                                PerAppMode.DISALLOWED -> R.string.per_app_disallowed
                            },
                        ),
                    )
                },
            )
        }
    }
    if (profile.perAppMode != PerAppMode.ALL) {
        OutlinedButton(onClick = { onPickApps(profile.perAppPackages.toSet()) }) {
            Text(stringResource(R.string.action_choose_apps, profile.perAppPackages.size))
        }
    }
}

@Composable
private fun SmartSplitSection(
    profile: Profile,
    onChange: (Profile) -> Unit,
) {
    Text(text = stringResource(R.string.section_smart_split), style = typography.titleMedium)

    SplitToggle(
        title = stringResource(R.string.split_bypass_lan_title),
        subtitle = stringResource(R.string.split_bypass_lan_subtitle),
        checked = profile.bypassLan,
        onCheckedChange = { v -> onChange(profile.copy(bypassLan = v)) },
    )
    SplitToggle(
        title = stringResource(R.string.split_bypass_cn_title),
        subtitle = stringResource(R.string.split_bypass_cn_subtitle),
        checked = profile.bypassCn,
        onCheckedChange = { v -> onChange(profile.copy(bypassCn = v)) },
    )
}

@Composable
private fun SplitToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = typography.bodyLarge)
            Text(text = subtitle, style = typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFields(
    config: PingtunnelConfig,
    onChange: (PingtunnelConfig) -> Unit,
) {
    var advancedShown by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = config.serverHost,
        onValueChange = { onChange(config.copy(serverHost = it)) },
        label = { Text(stringResource(R.string.field_server_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // v3：单一密码负责认证与加密（最少 8 字符），算法默认 ChaCha20-Poly1305
    OutlinedTextField(
        value = config.password,
        onValueChange = { onChange(config.copy(password = it)) },
        label = { Text(stringResource(R.string.field_password)) },
        supportingText = { Text(stringResource(R.string.field_password_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    // 加密算法覆盖：默认 ChaCha20-Poly1305，可换 AES；流量始终加密
    var cipherExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = cipherExpanded,
        onExpandedChange = { cipherExpanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(
                when (config.cipher) {
                    "aes256" -> R.string.cipher_aes256
                    "aes128" -> R.string.cipher_aes128
                    else -> R.string.cipher_default
                },
            ),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_cipher)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cipherExpanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = cipherExpanded,
            onDismissRequest = { cipherExpanded = false },
        ) {
            PingtunnelConfig.CIPHERS.forEach { cipher ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (cipher) {
                                    "aes256" -> R.string.cipher_aes256
                                    "aes128" -> R.string.cipher_aes128
                                    else -> R.string.cipher_default
                                },
                            ),
                        )
                    },
                    onClick = {
                        onChange(config.copy(cipher = cipher))
                        cipherExpanded = false
                    },
                )
            }
        }
    }

    TextButton(onClick = { advancedShown = !advancedShown }) {
        Text(
            stringResource(
                if (advancedShown) R.string.advanced_hide else R.string.advanced_show,
            ),
        )
    }

    if (advancedShown) {
        OutlinedTextField(
            value = config.timeout.toString(),
            onValueChange = { text -> onChange(config.copy(timeout = text.toIntOrNull() ?: 0)) },
            label = { Text(stringResource(R.string.field_timeout)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = if (config.maxConn > 0) config.maxConn.toString() else "",
            onValueChange = { text ->
                onChange(config.copy(maxConn = text.toIntOrNull() ?: 0))
            },
            label = { Text(stringResource(R.string.field_maxconn)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        SplitToggle(
            title = stringResource(R.string.field_congestion_title),
            subtitle = stringResource(R.string.field_congestion_subtitle),
            checked = config.congestion.isNotBlank(),
            onCheckedChange = { v ->
                onChange(
                    config.copy(
                        congestion = if (v) PingtunnelConfig.DEFAULT_CONGESTION else "",
                    ),
                )
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = stringResource(R.string.field_ipv6_title), style = typography.bodyLarge)
            Text(
                text = stringResource(R.string.field_ipv6_subtitle),
                style = typography.bodySmall,
            )
        }
        Switch(
            checked = config.enableIpv6,
            onCheckedChange = { onChange(config.copy(enableIpv6 = it)) },
        )
    }

    Spacer(modifier = Modifier.width(1.dp))
}
