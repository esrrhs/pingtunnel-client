package com.esrrhs.pingtunnel.client.ui

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_FAILED
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_SUCCESS
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_UNKNOWN
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_DIRECT
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_PROXY
import com.esrrhs.pingtunnel.client.util.Formatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HistoryResultFilter { ALL, SUCCESS, FAILED }
enum class HistoryIpFilter { ALL, IPV4, IPV6 }
enum class HistoryRouteFilter { ALL, PROXY, DIRECT }
enum class HistoryProtoFilter { ALL, TCP, UDP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<ConnectionLogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onDelete: (ConnectionLogEntry) -> Unit,
) {
    // 系统 BACK 回到主界面，与顶栏返回一致；清空确认弹窗打开时由 Dialog 优先消费
    BackHandler(onBack = onBack)
    var confirmClear by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var resultFilter by remember { mutableStateOf(HistoryResultFilter.ALL) }
    var ipFilter by remember { mutableStateOf(HistoryIpFilter.ALL) }
    var routeFilter by remember { mutableStateOf(HistoryRouteFilter.ALL) }
    var protoFilter by remember { mutableStateOf(HistoryProtoFilter.ALL) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val isAllSelected = resultFilter == HistoryResultFilter.ALL &&
        ipFilter == HistoryIpFilter.ALL &&
        routeFilter == HistoryRouteFilter.ALL &&
        protoFilter == HistoryProtoFilter.ALL

    val filteredEntries = remember(entries, resultFilter, ipFilter, routeFilter, protoFilter) {
        entries.filter { entry ->
            val matchResult = when (resultFilter) {
                HistoryResultFilter.ALL -> true
                HistoryResultFilter.SUCCESS -> entry.result == RESULT_SUCCESS
                HistoryResultFilter.FAILED -> entry.result == RESULT_FAILED
            }
            val matchIp = when (ipFilter) {
                HistoryIpFilter.ALL -> true
                HistoryIpFilter.IPV4 -> !entry.isIpv6
                HistoryIpFilter.IPV6 -> entry.isIpv6
            }
            val matchRoute = when (routeFilter) {
                HistoryRouteFilter.ALL -> true
                HistoryRouteFilter.PROXY -> entry.route == ROUTE_PROXY
                HistoryRouteFilter.DIRECT -> entry.route == ROUTE_DIRECT
            }
            val matchProto = when (protoFilter) {
                HistoryProtoFilter.ALL -> true
                HistoryProtoFilter.TCP -> entry.proto.equals("TCP", ignoreCase = true)
                HistoryProtoFilter.UDP -> entry.proto.equals("UDP", ignoreCase = true)
            }
            matchResult && matchIp && matchRoute && matchProto
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.history_clear),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (entries.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = isAllSelected,
                        onClick = {
                            resultFilter = HistoryResultFilter.ALL
                            ipFilter = HistoryIpFilter.ALL
                            routeFilter = HistoryRouteFilter.ALL
                            protoFilter = HistoryProtoFilter.ALL
                        },
                        label = { Text(stringResource(R.string.history_filter_all)) },
                    )

                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    FilterChip(
                        selected = resultFilter == HistoryResultFilter.SUCCESS,
                        onClick = {
                            resultFilter = if (resultFilter == HistoryResultFilter.SUCCESS) {
                                HistoryResultFilter.ALL
                            } else {
                                HistoryResultFilter.SUCCESS
                            }
                        },
                        label = { Text(stringResource(R.string.history_result_success)) },
                    )
                    FilterChip(
                        selected = resultFilter == HistoryResultFilter.FAILED,
                        onClick = {
                            resultFilter = if (resultFilter == HistoryResultFilter.FAILED) {
                                HistoryResultFilter.ALL
                            } else {
                                HistoryResultFilter.FAILED
                            }
                        },
                        label = { Text(stringResource(R.string.history_result_failed)) },
                    )

                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    FilterChip(
                        selected = ipFilter == HistoryIpFilter.IPV4,
                        onClick = {
                            ipFilter = if (ipFilter == HistoryIpFilter.IPV4) {
                                HistoryIpFilter.ALL
                            } else {
                                HistoryIpFilter.IPV4
                            }
                        },
                        label = { Text("IPv4") },
                    )
                    FilterChip(
                        selected = ipFilter == HistoryIpFilter.IPV6,
                        onClick = {
                            ipFilter = if (ipFilter == HistoryIpFilter.IPV6) {
                                HistoryIpFilter.ALL
                            } else {
                                HistoryIpFilter.IPV6
                            }
                        },
                        label = { Text("IPv6") },
                    )

                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    FilterChip(
                        selected = routeFilter == HistoryRouteFilter.PROXY,
                        onClick = {
                            routeFilter = if (routeFilter == HistoryRouteFilter.PROXY) {
                                HistoryRouteFilter.ALL
                            } else {
                                HistoryRouteFilter.PROXY
                            }
                        },
                        label = { Text(stringResource(R.string.history_route_proxy)) },
                    )
                    FilterChip(
                        selected = routeFilter == HistoryRouteFilter.DIRECT,
                        onClick = {
                            routeFilter = if (routeFilter == HistoryRouteFilter.DIRECT) {
                                HistoryRouteFilter.ALL
                            } else {
                                HistoryRouteFilter.DIRECT
                            }
                        },
                        label = { Text(stringResource(R.string.history_filter_direct)) },
                    )

                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    FilterChip(
                        selected = protoFilter == HistoryProtoFilter.TCP,
                        onClick = {
                            protoFilter = if (protoFilter == HistoryProtoFilter.TCP) {
                                HistoryProtoFilter.ALL
                            } else {
                                HistoryProtoFilter.TCP
                            }
                        },
                        label = { Text("TCP") },
                    )
                    FilterChip(
                        selected = protoFilter == HistoryProtoFilter.UDP,
                        onClick = {
                            protoFilter = if (protoFilter == HistoryProtoFilter.UDP) {
                                HistoryProtoFilter.ALL
                            } else {
                                HistoryProtoFilter.UDP
                            }
                        },
                        label = { Text("UDP") },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isAllSelected) {
                            stringResource(R.string.history_count, entries.size)
                        } else {
                            stringResource(R.string.history_count_filtered, filteredEntries.size, entries.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isAllSelected) {
                        TextButton(
                            onClick = {
                                resultFilter = HistoryResultFilter.ALL
                                ipFilter = HistoryIpFilter.ALL
                                routeFilter = HistoryRouteFilter.ALL
                                protoFilter = HistoryProtoFilter.ALL
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text(
                                stringResource(R.string.history_filter_reset),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.history_filter_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            resultFilter = HistoryResultFilter.ALL
                            ipFilter = HistoryIpFilter.ALL
                            routeFilter = HistoryRouteFilter.ALL
                            protoFilter = HistoryProtoFilter.ALL
                        }) {
                            Text(stringResource(R.string.history_filter_reset))
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(filteredEntries, key = { it.key + "@" + it.startMs }) { entry ->
                        HistoryCard(entry, nowMs, onDelete)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryCard(entry: ConnectionLogEntry, nowMs: Long, onDelete: (ConnectionLogEntry) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(entry.packageName)
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = absoluteTime(entry.endMs.takeIf { it > 0 } ?: entry.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ResultIcon(entry.result)
                IconButton(
                    onClick = { onDelete(entry) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.history_delete_one),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(entry.proto, MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.size(6.dp))
                Badge(
                    if (entry.isIpv6) "IPv6" else "IPv4",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                RouteBadge(entry.route)
                Spacer(Modifier.size(6.dp))
                ResultBadge(entry.result)
                entry.connectMs?.let { ms ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.history_connect_ms, ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(4.dp))
            Text(
                text = entry.domain ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.route == com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.ROUTE_PROXY &&
                !entry.proxyName.isNullOrBlank()
            ) {
                Text(
                    text = stringResource(R.string.conn_via_proxy, entry.proxyName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${entry.remoteIp}:${entry.remotePort}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val duration = (entry.endMs.takeIf { it > 0 }?.let { it - entry.startMs }) ?: 0L
            Text(
                text = stringResource(
                    R.string.history_detail_line,
                    Formatters.formatBytes(entry.txBytes),
                    Formatters.formatBytes(entry.rxBytes),
                    Formatters.formatDuration(duration),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entry.result == RESULT_FAILED && !entry.reason.isNullOrBlank()) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = stringResource(R.string.history_reason, entry.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RouteBadge(route: String) {
    when (route) {
        ROUTE_DIRECT -> Badge(
            stringResource(R.string.conn_direct),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ROUTE_PROXY -> Badge(
            stringResource(R.string.history_route_proxy),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Badge(
            stringResource(R.string.history_route_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultBadge(result: String) {
    when (result) {
        RESULT_SUCCESS -> Badge(
            stringResource(R.string.history_result_success),
            Color(0xFF1E3A24),
            Color(0xFF7FD896),
        )
        RESULT_FAILED -> Badge(
            stringResource(R.string.history_result_failed),
            Color(0xFF3D1E1E),
            Color(0xFFF08A8A),
        )
        else -> Badge(
            stringResource(R.string.history_result_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultIcon(result: String) {
    val (icon, tint) = when (result) {
        RESULT_SUCCESS -> Icons.Filled.CheckCircle to Color(0xFF5FB878)
        RESULT_FAILED -> Icons.Filled.HighlightOff to Color(0xFFE05252)
        else -> Icons.Filled.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

@Composable
private fun absoluteTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

@Composable
private fun AppIcon(packageName: String?) {
    val context = LocalContext.current
    val painter = androidx.compose.runtime.produceState<BitmapPainter?>(null, packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
