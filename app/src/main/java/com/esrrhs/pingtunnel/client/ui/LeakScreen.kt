package com.esrrhs.pingtunnel.client.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.esrrhs.pingtunnel.client.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeakScreen(
    state: LeakState,
    connected: Boolean,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    // 系统 BACK 回到主界面，与顶栏返回一致
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.leak_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRun,
                enabled = connected && state !is LeakState.Running,
            ) {
                Text(stringResource(R.string.leak_run))
            }
            if (!connected) {
                Text(
                    text = stringResource(R.string.leak_need_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is LeakState.Running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.leak_running))
                }
            }
            if (state is LeakState.Done) ResultCards(state)

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.leak_webrtc_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.leak_webrtc_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExternalLink(stringResource(R.string.leak_webrtc_browserleaks), BROWSERLEAKS_WEBRTC)
                    ExternalLink(stringResource(R.string.leak_webrtc_ipleak), IPLEAK_URL)
                }
            }
        }
    }
}

@Composable
private fun ResultCards(state: LeakState.Done) {
    val r = state.report
    val dash = stringResource(R.string.value_dash)

    InfoCard(
        title = stringResource(R.string.leak_ipv4_title),
        lines = listOf(
            stringResource(R.string.check_direct_ip, r.ipv4Direct ?: dash),
            stringResource(R.string.check_proxy_ip, r.ipv4Proxy ?: dash),
        ),
    )

    InfoCard(
        title = stringResource(R.string.leak_ipv6_title),
        lines = buildList {
            add(stringResource(R.string.leak_ipv6_direct, r.ipv6Direct ?: dash))
            add(stringResource(R.string.leak_ipv6_proxy, r.ipv6Proxy ?: dash))
            if (r.ipv6Proxy != null) add(stringResource(R.string.leak_ipv6_tunnel_ok))
        },
    )

    InfoCard(
        title = stringResource(R.string.leak_dns_title),
        lines = buildList {
            when {
                r.dnsTestFailed -> add(stringResource(R.string.leak_dns_failed))
                r.dnsResolvers.isEmpty() -> add(stringResource(R.string.leak_dns_none))
                else -> {
                    add(stringResource(R.string.leak_dns_ok, r.dnsResolvers.size))
                    r.dnsResolvers.forEach { resolver ->
                        val meta = listOf(resolver.country, resolver.asnName)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        add("· ${resolver.ip}" + if (meta.isNotBlank()) "  $meta" else "")
                    }
                }
            }
        },
    )
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExternalLink(text: String, url: String) {
    val context = LocalContext.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .padding(vertical = 2.dp),
    )
}

private const val BROWSERLEAKS_WEBRTC = "https://browserleaks.com/webrtc"
private const val IPLEAK_URL = "https://ipleak.net/"
