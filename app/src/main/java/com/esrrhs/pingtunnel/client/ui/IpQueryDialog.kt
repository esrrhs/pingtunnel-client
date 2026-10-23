package com.esrrhs.pingtunnel.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.util.IpGeoInfo

@Composable
fun IpQueryDialog(
    state: IpQueryState,
    connected: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        IpQueryState.Idle -> Unit
        IpQueryState.Running -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ip_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.ip_querying),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )

        is IpQueryState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ip_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.tunnel?.let {
                        IpInfoBlock(
                            icon = Icons.Filled.Security,
                            title = stringResource(R.string.ip_exit),
                            info = it,
                        )
                    }
                    state.direct?.let {
                        IpInfoBlock(
                            icon = Icons.Filled.Smartphone,
                            title = stringResource(R.string.ip_direct),
                            info = it,
                        )
                    }
                    if (state.tunnel == null && state.direct == null) {
                        Text(
                            text = stringResource(R.string.ip_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!connected) {
                        Text(
                            text = stringResource(R.string.ip_not_connected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ip_refresh))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun IpInfoBlock(
    icon: ImageVector,
    title: String,
    info: IpGeoInfo,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.flagEmoji.isNotEmpty()) {
                Text(text = info.flagEmoji, fontSize = 22.sp)
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = info.ip,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (info.locationLine.isNotBlank()) {
            Text(
                text = info.locationLine,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        info.isp?.let { isp ->
            Text(
                text = isp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
