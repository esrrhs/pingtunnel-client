package com.esrrhs.pingtunnel.client.ui

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.util.AppConnectionGroup
import com.esrrhs.pingtunnel.client.util.Formatters
import com.esrrhs.pingtunnel.client.util.LiveConnection
import com.esrrhs.pingtunnel.client.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    groups: List<AppConnectionGroup>,
    vpnState: VpnState,
    activeProfile: Profile?,
    onBack: () -> Unit,
) {
    // 系统 BACK 回到主界面，与顶栏返回一致（避免录制导航时退出 Activity）
    BackHandler(onBack = onBack)

    // 每秒刷新一次，让「已连接时长」走动
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val connected = vpnState is VpnState.Connected
        when {
            !connected -> EmptyHint(
                text = stringResource(R.string.conn_disconnected_hint),
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                item {
                    ProxyBanner(activeProfile)
                }
                if (groups.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.conn_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
                items(groups, key = { it.uid }) { group ->
                    AppConnectionCard(group, nowMs)
                }
            }
        }
    }
}

/** 当前活动代理（隧道出口）信息条。 */
@Composable
private fun ProxyBanner(profile: Profile?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.VpnKey,
                contentDescription = null,
                tint = SsGreenBadge,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.conn_proxy_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = profile?.let { "${it.name} · ${it.config.displayProto} ${it.config.serverHost}" }
                        ?: stringResource(R.string.conn_proxy_none),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SsGreenBadge = androidx.compose.ui.graphics.Color(0xFF5FB878)

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun AppConnectionCard(group: AppConnectionGroup, nowMs: Long) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(group.packageName)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val activeCount = group.connections.count { it.active }
                    val directCount = group.connections.count { it.direct }
                    val subtitle = buildList {
                        add(
                            stringResource(
                                if (group.hasActive) R.string.conn_count
                                else R.string.conn_count_closed,
                                group.connections.size,
                            ),
                        )
                        if (group.hasActive && activeCount != group.connections.size) {
                            add(stringResource(R.string.conn_active_count, activeCount))
                        }
                        if (directCount > 0) {
                            add(stringResource(R.string.conn_direct_count, directCount))
                        }
                    }.joinToString(" · ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.hasActive) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "↑ ${Formatters.formatRate(group.txRate)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "↓ ${Formatters.formatRate(group.rxRate)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.conn_totals,
                    Formatters.formatBytes(group.txTotal),
                    Formatters.formatBytes(group.rxTotal),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            group.connections.forEach { conn ->
                ConnectionRow(conn, nowMs)
            }
        }
    }
}

@Composable
private fun ConnectionRow(conn: LiveConnection, nowMs: Long) {
    val timeText = remember(conn.createdMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(conn.createdMs))
    }
    val duration = (nowMs - conn.createdMs).coerceAtLeast(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (conn.active) Modifier else Modifier.alpha(0.55f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = conn.protocol,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.secondary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (conn.direct) {
                Text(
                    text = stringResource(R.string.conn_direct),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = conn.domain ?: "${conn.remoteIp}:${conn.remotePort}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (conn.domain != null) FontFamily.Default else FontFamily.Monospace,
                ),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conn.active) {
                Text(
                    text = stringResource(
                        R.string.conn_since,
                        timeText,
                        Formatters.formatDuration(duration),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.conn_closed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(modifier = Modifier.padding(start = 40.dp, top = 1.dp)) {
            val total = stringResource(
                R.string.conn_pair,
                Formatters.formatBytes(conn.txBytes),
                Formatters.formatBytes(conn.rxBytes),
            )
            // 活跃行：速率 · 累计；已结束行：仅冻结的累计值
            val text = if (conn.active) {
                val rate = stringResource(
                    R.string.conn_pair,
                    Formatters.formatRate(conn.txRate),
                    Formatters.formatRate(conn.rxRate),
                )
                // fake-IP 场景主行显示域名，次行补显真实(映射)IP 与端口
                val endpoint = conn.domain?.let { "${conn.remoteIp}:${conn.remotePort}　·　" } ?: ""
                "$endpoint$rate　·　$total"
            } else {
                val endpoint = conn.domain?.let { "${conn.remoteIp}:${conn.remotePort}　·　" } ?: ""
                "$endpoint$total"
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String?) {
    val context = LocalContext.current
    val painter = androidx.compose.runtime.produceState<BitmapPainter?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val d: Drawable = if (packageName != null) {
                    pm.getApplicationIcon(packageName)
                } else {
                    pm.defaultActivityIcon
                }
                BitmapPainter(d.toBitmap(96, 96).asImageBitmap())
            }.getOrNull()
        }
    }.value
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", style = MaterialTheme.typography.titleSmall)
        }
    }
}
