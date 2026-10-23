package com.esrrhs.pingtunnel.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.util.TunnelStatus

@Composable
fun SelfCheckDialog(
    state: SelfCheckState,
    onDismiss: () -> Unit,
) {
    when (state) {
        SelfCheckState.Idle -> Unit
        SelfCheckState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.check_running_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.check_running_text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
        )

        is SelfCheckState.Done -> {
            val r = state.result
            val (titleRes, summaryRes) = when (r.status) {
                TunnelStatus.PROXY_OK -> R.string.check_ok_title to R.string.check_ok_text
                TunnelStatus.SAME_IP -> R.string.check_same_title to R.string.check_same_text
                TunnelStatus.PROXY_FAILED ->
                    R.string.check_proxy_failed_title to R.string.check_proxy_failed_text
                TunnelStatus.NETWORK_FAILED ->
                    R.string.check_network_failed_title to R.string.check_network_failed_text
            }
            val dash = stringResource(R.string.value_dash)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(titleRes)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(summaryRes), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(
                                R.string.check_direct_ip, r.directIp ?: dash,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.check_proxy_ip, r.proxyIp ?: dash,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                if (r.dnsViaProxy) R.string.check_dns_ok
                                else R.string.check_dns_bad,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (r.latencyMs >= 0) {
                            Text(
                                text = stringResource(R.string.check_latency, r.latencyMs),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                },
            )
        }
    }
}
