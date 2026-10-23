package com.esrrhs.pingtunnel.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.util.Formatters
import com.esrrhs.pingtunnel.client.util.TrafficTotals
import com.esrrhs.pingtunnel.client.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    profiles: List<Profile>,
    activeId: String?,
    vpnState: VpnState,
    session: SessionTraffic,
    onResetTraffic: () -> Unit,
    onResetProfileTraffic: (String) -> Unit,
    onBack: () -> Unit,
) {
    var confirmResetAll by remember { mutableStateOf(false) }
    var confirmResetId by remember { mutableStateOf<String?>(null) }

    // 系统 BACK 回到主界面，与顶栏返回一致（避免录制导航时退出 Activity）
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
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
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vpnState is VpnState.Connected || vpnState is VpnState.Connecting) {
                SessionCard(activeId, vpnState, session, profiles)
            }

            val totals = TrafficTotals.of(profiles)
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.stats_totals), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(
                            R.string.stats_total_line,
                            Formatters.formatBytes(totals.txBytes),
                            Formatters.formatBytes(totals.rxBytes),
                            Formatters.formatBytes(totals.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Text(
                stringResource(R.string.stats_per_profile),
                style = MaterialTheme.typography.titleSmall,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileTrafficRow(
                        profile = profile,
                        active = profile.id == activeId &&
                            (vpnState is VpnState.Connected || vpnState is VpnState.Connecting),
                        session = session,
                        onReset = { confirmResetId = profile.id },
                    )
                }
            }

            OutlinedButton(
                onClick = { confirmResetAll = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text(stringResource(R.string.action_reset_all))
            }
        }
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text(stringResource(R.string.stats_reset_all_title)) },
            text = { Text(stringResource(R.string.stats_reset_all_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetTraffic()
                    confirmResetAll = false
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val resetTarget = confirmResetId
    if (resetTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmResetId = null },
            title = { Text(stringResource(R.string.stats_reset_one_title)) },
            text = { Text(stringResource(R.string.stats_reset_one_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetProfileTraffic(resetTarget)
                    confirmResetId = null
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SessionCard(
    activeId: String?,
    vpnState: VpnState,
    session: SessionTraffic,
    profiles: List<Profile>,
) {
    val activeProfile = profiles.firstOrNull { it.id == activeId }
    // 当前配置总量 = 历史累计 + 本会话实时
    val tx = (activeProfile?.txBytes ?: 0L) + session.tx
    val rx = (activeProfile?.rxBytes ?: 0L) + session.rx
    val durationMs = session.connectedAtMs?.let { System.currentTimeMillis() - it } ?: 0L

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    if (vpnState is VpnState.Connected) R.string.stats_current_session
                    else R.string.stats_reconnecting,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.stats_duration,
                    Formatters.formatDuration(durationMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.stats_up_rate,
                    Formatters.formatBytes(tx),
                    Formatters.formatRate(session.txRate),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.stats_down_rate,
                    Formatters.formatBytes(rx),
                    Formatters.formatRate(session.rxRate),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProfileTrafficRow(
    profile: Profile,
    active: Boolean,
    session: SessionTraffic,
    onReset: () -> Unit,
) {
    val tx = profile.txBytes + if (active) session.tx else 0L
    val rx = profile.rxBytes + if (active) session.rx else 0L
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.format_traffic,
                        Formatters.formatBytes(tx),
                        Formatters.formatBytes(rx),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.action_reset_short))
            }
        }
    }
}
