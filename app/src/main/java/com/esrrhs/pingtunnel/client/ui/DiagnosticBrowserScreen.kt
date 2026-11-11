package com.esrrhs.pingtunnel.client.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.data.TimingRecord
import com.esrrhs.pingtunnel.client.proxy.InternalPipelineTiming
import com.esrrhs.pingtunnel.client.proxy.InternalProxyTrace
import com.esrrhs.pingtunnel.client.proxy.ProxyEventBus
import com.esrrhs.pingtunnel.client.util.BrowserTimingBridge
import com.esrrhs.pingtunnel.client.vpn.ActiveSession
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableLongStateOf

// =========================================================================
// 甘特图分条独立配色方案 (每条甘特图内部颜色唯一且对比强烈，允许跨甘特图重复)
// =========================================================================

// 序列 0: 预备阶段 (3个阶段，冷暖对比强烈)
private val ColorDispatch = Color(0xFF1E88E5)       // 亮蓝: UI 点击 -> WebKit 引擎调度 / IPC
private val ColorPrecheck = Color(0xFFFF8F00)       // 琥珀金橙: 缓存比对 / 策略判定 / 重定向
private val ColorQueueing = Color(0xFF8E24AA)       // 紫色: 网络排队 / Socket 准备

// 序列 1: 浏览器网络时序 (Chrome DevTools 经典高辨识度原色方案，各阶段色相完全不同)
private val ColorDns = Color(0xFF039BE5)            // 亮天蓝: DNS 查询
private val ColorConnect = Color(0xFFFFB300)        // 明黄橙: TCP 建连
private val ColorSsl = Color(0xFF8E24AA)            // 紫色: SSL/TLS 握手
private val ColorTtfb = Color(0xFF43A047)           // 鲜绿: 等待首字节 (TTFB / Server Response)
private val ColorDownload = Color(0xFF00ACC1)       // 青碧色: 内容下载 (Content Download)
private val ColorOther = Color(0xFF757575)          // 中性灰: 排队/未分类

// 序列 2: 内核底层代理链路 (5个连续子阶段，红/黄/绿/蓝/紫 饱和原色对比，绝对不混淆)
private val ColorInternalRoute = Color(0xFFE53935)      // 红色: 分流路由判定
private val ColorInternalTunnel = Color(0xFFFB8C00)     // 橙黄: 目标 DNS / 上游隧道建连
private val ColorInternalHandshake = Color(0xFF43A047)  // 绿色: SOCKS5 握手确认
private val ColorInternalWait = Color(0xFF1E88E5)       // 蓝色: 等待首包响应 (Wait First Packet)
private val ColorInternalPipe = Color(0xFF8E24AA)       // 紫色: 双向数据转发传输
private val ColorTrigger = Color(0xFFE91E63)            // 鲜亮洋红: 用户回车触发上网起点

// 序列 3 & 4: 底层通道协议帧 (5种帧类型，经典红黄蓝绿紫极高反差)
private val ColorFrameOpen = Color(0xFF1E88E5)          // 亮蓝: OPEN (建连)
private val ColorFrameOpenRsp = Color(0xFF43A047)       // 鲜绿: OPENRSP (就绪)
private val ColorFrameDataUp = Color(0xFF00ACC1)        // 青蓝: DATA (↑上传)
private val ColorFrameDataDown = Color(0xFFFF8F00)      // 亮橙: DATA (↓下载)
private val ColorFrameClose = Color(0xFFE53935)         // 鲜红: CLOSE (关闭)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticBrowserScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isPageLoading by remember { mutableStateOf(false) }
    var showDevTools by remember { mutableStateOf(true) }
    var devToolsHeightFraction by remember { mutableFloatStateOf(0.45f) }
    var userTriggerTimestampMs by remember { mutableLongStateOf(0L) }

    val timingBridge = remember { BrowserTimingBridge() }
    val records by timingBridge.records.collectAsState()
    val internalPipelines by ProxyEventBus.pipelines.collectAsState()

    val hasAbsoluteTiming = records.any { it.absoluteStartMs > 0L }
    val (timelineBaseMs, totalTimelineMs) = remember(records, internalPipelines, userTriggerTimestampMs, hasAbsoluteTiming) {
        if (hasAbsoluteTiming) {
            val recordStarts = records.filter { it.absoluteStartMs > 0L }.map { it.absoluteStartMs }
            val recordEnds = records.filter { it.absoluteStartMs > 0L }.map { it.absoluteStartMs + it.duration.toLong() }
            val pipelineStarts = internalPipelines.filter { it.startTimeMs > 0L }.map { it.startTimeMs }
            val pipelineEnds = internalPipelines.filter { it.startTimeMs > 0L }.map { it.startTimeMs + it.totalTime }

            val allStarts = mutableListOf<Long>()
            allStarts.addAll(recordStarts)
            allStarts.addAll(pipelineStarts)
            if (userTriggerTimestampMs > 0L) {
                val minEvent = allStarts.minOrNull() ?: userTriggerTimestampMs
                if (Math.abs(userTriggerTimestampMs - minEvent) < 30_000L) {
                    allStarts.add(userTriggerTimestampMs)
                }
            }

            val base = allStarts.minOrNull() ?: 0L
            val maxEnd = (recordEnds + pipelineEnds).maxOrNull() ?: (base + 1000L)
            val span = (maxEnd - base).coerceAtLeast(100L).toDouble()
            Pair(base, span)
        } else {
            val span = records.maxOfOrNull { it.startTime + it.duration }?.coerceAtLeast(10.0) ?: 100.0
            Pair(0L, span)
        }
    }

    var selectedRecord by remember { mutableStateOf<TimingRecord?>(null) }
    var selectedPipeline by remember { mutableStateOf<InternalPipelineTiming?>(null) }

    BackHandler {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val executor = Executor { it.run() }
                ProxyController.getInstance().clearProxyOverride(executor) {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val target = sanitizeUrl(urlInput)
                                urlInput = target
                                currentUrl = target
                                timingBridge.currentPageUrl = target
                                selectedRecord = null
                                val now = System.currentTimeMillis()
                                userTriggerTimestampMs = now
                                timingBridge.lastUserTriggerMs = now
                                webViewInstance?.loadUrl(target)
                            }),
                            trailingIcon = {
                                if (urlInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { urlInput = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = {
                            val target = sanitizeUrl(urlInput)
                            urlInput = target
                            currentUrl = target
                            timingBridge.currentPageUrl = target
                            selectedRecord = null
                            val now = System.currentTimeMillis()
                            userTriggerTimestampMs = now
                            timingBridge.lastUserTriggerMs = now
                            webViewInstance?.loadUrl(target)
                        }) {
                            Icon(Icons.Filled.ArrowForward, contentDescription = stringResource(R.string.diag_go))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        selectedRecord = null
                        userTriggerTimestampMs = System.currentTimeMillis()
                        webViewInstance?.reload()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diag_refresh))
                    }
                    IconButton(onClick = { showDevTools = !showDevTools }) {
                        BadgedBox(
                            badge = {
                                if (records.isNotEmpty()) {
                                    Badge { Text("${records.size}") }
                                }
                            }
                        ) {
                            Icon(
                                if (showDevTools) Icons.Filled.KeyboardArrowDown else Icons.Filled.Timeline,
                                contentDescription = stringResource(R.string.diag_toggle_devtools),
                                tint = if (showDevTools) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 加载进度条
            if (isPageLoading && loadProgress < 100) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // WebView 与时序面板的垂直布局
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (showDevTools) (1f - devToolsHeightFraction) else 1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        createWebView(
                            context = ctx,
                            timingBridge = timingBridge,
                            onProgressChanged = { loadProgress = it },
                            onLoadingStateChanged = { loading, url ->
                                isPageLoading = loading
                                if (url != null) {
                                    urlInput = url
                                    currentUrl = url
                                    timingBridge.currentPageUrl = url
                                }
                            }
                        ).also {
                            webViewInstance = it
                            it.loadUrl(currentUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 时序面板 (时序表与 Console 日志)
            AnimatedVisibility(visible = showDevTools) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(devToolsHeightFraction / (1f - devToolsHeightFraction + devToolsHeightFraction)),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header: 标题与操作栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Timeline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${stringResource(R.string.diag_tab_unified)} (${records.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (internalPipelines.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "• 内核连接: ${internalPipelines.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    timingBridge.clear()
                                    ProxyEventBus.clearTraces()
                                    userTriggerTimestampMs = 0L
                                    selectedRecord = null
                                    selectedPipeline = null
                                    webViewInstance?.evaluateJavascript("if (window.__clearTimingBuffer) window.__clearTimingBuffer();", null)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                devToolsHeightFraction = if (devToolsHeightFraction < 0.6f) 0.75f else 0.40f
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (devToolsHeightFraction > 0.5f) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 阶段色彩图例（包含浏览器阶段与内核阶段）
                        TimingLegendBar(hasTrigger = userTriggerTimestampMs > 0L)

                        HorizontalDivider()

                        // 整合后的统一瀑布流时序列表
                        UnifiedWaterfallTab(
                            records = records,
                            pipelines = internalPipelines,
                            userTriggerTimestampMs = userTriggerTimestampMs,
                            selected = selectedRecord,
                            onSelect = { selectedRecord = it }
                        )
                    }
                }
            }
        }
    }

    // 统一资源与内核详情弹出框
    if (selectedRecord != null) {
        val rec = selectedRecord!!
        val matchedPipeline = remember(rec) {
            val host = runCatching { URI(rec.name).host }.getOrNull()
            ProxyEventBus.findPipeline(host, rec.absoluteStartMs)
        }
        val hostTraces = remember(rec) {
            val host = runCatching { URI(rec.name).host }.getOrNull()?.trimEnd('.').orEmpty()
            if (host.isNotBlank()) {
                ProxyEventBus.traces.value.filter {
                    it.host.trimEnd('.').equals(host, ignoreCase = true)
                }
            } else emptyList()
        }
        RecordDetailDialog(
            record = rec,
            pipeline = matchedPipeline,
            traces = hostTraces,
            timelineBaseMs = timelineBaseMs,
            totalTimelineMs = totalTimelineMs,
            triggerTimeMs = userTriggerTimestampMs,
            onDismiss = { selectedRecord = null }
        )
    }
}

@Composable
private fun TimingLegendBar(hasTrigger: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasTrigger) {
            LegendDot(ColorTrigger, stringResource(R.string.diag_trigger_legend))
        }
        LegendDot(ColorDns, stringResource(R.string.diag_phase_dns))
        LegendDot(ColorConnect, stringResource(R.string.diag_phase_connect))
        LegendDot(ColorSsl, stringResource(R.string.diag_phase_ssl))
        LegendDot(ColorTtfb, stringResource(R.string.diag_phase_ttfb))
        LegendDot(ColorDownload, stringResource(R.string.diag_phase_download))
        LegendDot(ColorInternalRoute, stringResource(R.string.diag_internal_route))
        LegendDot(ColorInternalTunnel, stringResource(R.string.diag_internal_tunnel))
        LegendDot(ColorInternalHandshake, stringResource(R.string.diag_internal_handshake))
        LegendDot(ColorInternalWait, stringResource(R.string.diag_internal_wait))
        LegendDot(ColorInternalPipe, stringResource(R.string.diag_internal_pipe))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

@Composable
private fun UnifiedWaterfallTab(
    records: List<TimingRecord>,
    pipelines: List<InternalPipelineTiming>,
    userTriggerTimestampMs: Long,
    selected: TimingRecord?,
    onSelect: (TimingRecord) -> Unit,
) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.diag_empty_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 计算全局统一时间基准轴:
    // 如果有绝对时间戳 (timeOrigin > 0)，以 triggerTimeMs 与各记录/连接的起始时间计算基准
    // 否则回退为以 record.startTime 为基准的相对时间
    val hasAbsoluteTiming = records.any { it.absoluteStartMs > 0L }

    val (timelineBaseMs, totalTimelineMs) = remember(records, pipelines, userTriggerTimestampMs, hasAbsoluteTiming) {
        if (hasAbsoluteTiming) {
            val recordStarts = records.filter { it.absoluteStartMs > 0L }.map { it.absoluteStartMs }
            val recordEnds = records.filter { it.absoluteStartMs > 0L }.map { it.absoluteStartMs + it.duration.toLong() }
            val pipelineStarts = pipelines.filter { it.startTimeMs > 0L }.map { it.startTimeMs }
            val pipelineEnds = pipelines.filter { it.startTimeMs > 0L }.map { it.startTimeMs + it.totalTime }

            val allStarts = mutableListOf<Long>()
            allStarts.addAll(recordStarts)
            allStarts.addAll(pipelineStarts)
            if (userTriggerTimestampMs > 0L) {
                // 如果 trigger 时间和记录在合理区间内 (例如前后 30 秒)，纳入基准
                val minEvent = allStarts.minOrNull() ?: userTriggerTimestampMs
                if (Math.abs(userTriggerTimestampMs - minEvent) < 30_000L) {
                    allStarts.add(userTriggerTimestampMs)
                }
            }

            val base = allStarts.minOrNull() ?: 0L
            val maxEnd = (recordEnds + pipelineEnds).maxOrNull() ?: (base + 1000L)
            val span = (maxEnd - base).coerceAtLeast(100L).toDouble()
            Pair(base, span)
        } else {
            val span = records.maxOfOrNull { it.startTime + it.duration }?.coerceAtLeast(10.0) ?: 100.0
            Pair(0L, span)
        }
    }

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 如果有用户回车触发上网时间，且在时间轴基准内，展示出发时间提示指示
        if (userTriggerTimestampMs > 0L) {
            val timeStr = remember(userTriggerTimestampMs) {
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(userTriggerTimestampMs))
            }
            Surface(
                color = ColorTrigger.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(ColorTrigger)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diag_trigger_origin, timeStr),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = ColorTrigger,
                        fontSize = 11.sp,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = ColorTrigger.copy(alpha = 0.3f))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(records) { r ->
                val host = remember(r.name) {
                    runCatching { URI(r.name).host }.getOrNull()
                }
                val matchedPipeline = remember(host, pipelines, r.absoluteStartMs) {
                    ProxyEventBus.findPipeline(host, r.absoluteStartMs)
                }

                UnifiedWaterfallRow(
                    record = r,
                    pipeline = matchedPipeline,
                    timelineBaseMs = timelineBaseMs,
                    totalTimelineMs = totalTimelineMs,
                    isSelected = r == selected,
                    onClick = { onSelect(r) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun UnifiedWaterfallRow(
    record: TimingRecord,
    pipeline: InternalPipelineTiming?,
    timelineBaseMs: Long,
    totalTimelineMs: Double,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val displayName = remember(record.name) {
        runCatching {
            val uri = URI(record.name)
            val path = uri.path?.substringAfterLast('/').orEmpty()
            if (path.isNotBlank()) path else (uri.host ?: record.name)
        }.getOrDefault(record.name)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 名称与类型
        Column(modifier = Modifier.width(115.dp)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.initiatorType.ifBlank { "other" },
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pipeline != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (pipeline.isDirect) "DIRECT" else "TUNNEL",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        fontSize = 8.sp,
                        color = if (pipeline.isDirect) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                    )
                } else if (record.nextHopProtocol.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = record.nextHopProtocol,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.width(6.dp))

        // 双层可视化甘特条 (上条: 浏览器网络时序; 下条: 内部代理运转链路)
        // 关键改进: 基于统一时间原点计算浏览器条和内核条的起始偏移量，使二者真实时序严格对齐！
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 计算浏览器请求在时间轴上的物理起始位置
            val browserStartOffsetMs = if (timelineBaseMs > 0L && record.absoluteStartMs > 0L) {
                (record.absoluteStartMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
            } else {
                record.startTime
            }
            val browserStartWeight = (browserStartOffsetMs / totalTimelineMs).coerceIn(0.0, 0.95).toFloat()
            val browserDurationWeight = (record.duration / totalTimelineMs).coerceIn(0.01, 1.0).toFloat()

            // 浏览器视角网络瀑布流
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                val totalWidth = maxWidth
                val leftOffset = totalWidth * browserStartWeight
                val barWidth = (totalWidth * browserDurationWeight).coerceAtLeast(3.dp)

                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .offset(x = leftOffset)
                        .width(barWidth)
                ) {
                    if (record.duration > 0) {
                        val d = record.duration
                        val dnsW = (record.dnsDuration / d).toFloat().coerceIn(0f, 1f)
                        val connW = ((record.connectDuration - record.sslDuration).coerceAtLeast(0.0) / d).toFloat().coerceIn(0f, 1f)
                        val sslW = (record.sslDuration / d).toFloat().coerceIn(0f, 1f)
                        val ttfbW = (record.ttfb / d).toFloat().coerceIn(0f, 1f)
                        val downW = (record.downloadDuration / d).toFloat().coerceIn(0f, 1f)
                        val accountedW = dnsW + connW + sslW + ttfbW + downW
                        val otherW = (1f - accountedW).coerceIn(0f, 1f)

                        if (otherW > 0.05f && (record.cacheOrPrecheckDuration > 0 || record.queueingDuration > 0)) {
                            Box(Modifier.fillMaxHeight().weight(otherW).background(ColorQueueing))
                        }
                        if (dnsW > 0) Box(Modifier.fillMaxHeight().weight(dnsW).background(ColorDns))
                        if (connW > 0) Box(Modifier.fillMaxHeight().weight(connW).background(ColorConnect))
                        if (sslW > 0) Box(Modifier.fillMaxHeight().weight(sslW).background(ColorSsl))
                        if (ttfbW > 0) Box(Modifier.fillMaxHeight().weight(ttfbW).background(ColorTtfb))
                        if (downW > 0) Box(Modifier.fillMaxHeight().weight(downW).background(ColorDownload))

                        if (dnsW <= 0 && connW <= 0 && sslW <= 0 && ttfbW <= 0 && downW <= 0 && otherW <= 0.05f) {
                            Box(Modifier.fillMaxSize().background(ColorOther))
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(ColorOther))
                    }
                }
            }

            // 内核代理链路剖析条 (按内核真实的 startTimeMs 偏移，精确对齐浏览器建立连接与等待首包阶段)
            if (pipeline != null && pipeline.totalTime > 0) {
                val pipeStartOffsetMs = if (timelineBaseMs > 0L && pipeline.startTimeMs > 0L) {
                    (pipeline.startTimeMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
                } else {
                    browserStartOffsetMs
                }
                val pipeStartWeight = (pipeStartOffsetMs / totalTimelineMs).coerceIn(0.0, 0.95).toFloat()
                val pipeDurationWeight = (pipeline.totalTime.toDouble() / totalTimelineMs).coerceIn(0.01, 1.0).toFloat()
                val d = pipeline.totalTime.toDouble()

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    val totalWidth = maxWidth
                    val leftOffset = totalWidth * pipeStartWeight
                    val barWidth = (totalWidth * pipeDurationWeight).coerceAtLeast(3.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .offset(x = leftOffset)
                            .width(barWidth)
                    ) {
                        val activeDuration = (pipeline.routeMs + pipeline.dnsOrConnectMs + pipeline.handshakeMs + pipeline.waitFirstDataMs + pipeline.dataTransferMs).toDouble()
                        val safeD = if (activeDuration > 0.0) maxOf(d, activeDuration) else d

                        val routeW = (pipeline.routeMs / safeD).toFloat().coerceIn(0f, 1f)
                        val connW = (pipeline.dnsOrConnectMs / safeD).toFloat().coerceIn(0f, 1f)
                        val hsW = (pipeline.handshakeMs / safeD).toFloat().coerceIn(0f, 1f)
                        val waitW = (pipeline.waitFirstDataMs / safeD).toFloat().coerceIn(0f, 1f)
                        val pipeW = (pipeline.dataTransferMs / safeD).toFloat().coerceIn(0f, 1f)
                        val accountedW = routeW + connW + hsW + waitW + pipeW
                        val idleW = (1f - accountedW).coerceIn(0f, 1f)

                        if (routeW > 0) Box(Modifier.fillMaxHeight().weight(routeW).background(ColorInternalRoute))
                        if (connW > 0) Box(Modifier.fillMaxHeight().weight(connW).background(ColorInternalTunnel))
                        if (hsW > 0) Box(Modifier.fillMaxHeight().weight(hsW).background(ColorInternalHandshake))
                        if (waitW > 0) Box(Modifier.fillMaxHeight().weight(waitW).background(ColorInternalWait))
                        if (pipeW > 0) Box(Modifier.fillMaxHeight().weight(pipeW).background(ColorInternalPipe))
                        if (idleW > 0.02f) Box(Modifier.fillMaxHeight().weight(idleW).background(ColorOther.copy(alpha = 0.5f)))

                        if (routeW <= 0 && connW <= 0 && hsW <= 0 && waitW <= 0 && pipeW <= 0 && idleW <= 0.02f) {
                            Box(Modifier.fillMaxSize().background(ColorOther))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 总耗时数字
        Column(modifier = Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
            Text(
                text = "${record.duration.toInt()} ms",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontSize = 11.sp,
            )
            if (pipeline != null) {
                Text(
                    text = "${pipeline.totalTime} ms",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDetailDialog(
    record: TimingRecord,
    pipeline: InternalPipelineTiming?,
    traces: List<InternalProxyTrace> = emptyList(),
    timelineBaseMs: Long = 0L,
    totalTimelineMs: Double = 100.0,
    triggerTimeMs: Long,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.diag_timing_detail_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 卡片 1: 基础请求概览与触发时差
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Text(
                            text = record.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))

                        if (triggerTimeMs > 0L && record.absoluteStartMs > 0L) {
                            val triggerDeltaMs = (record.absoluteStartMs - triggerTimeMs).coerceAtLeast(0L)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "从回车出发到本请求发起",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = ColorTrigger
                                )
                                Text(
                                    text = "+$triggerDeltaMs ms",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = ColorTrigger
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "请求类型 / 协议",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${record.initiatorType.ifBlank { "resource" }} ${record.nextHopProtocol.let { if (it.isNotBlank()) "· $it" else "" }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }

                        if (record.transferSize > 0) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.diag_transfer_size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${record.transferSize} bytes (解码 ${record.decodedBodySize} bytes)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }

                        if (pipeline != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "SPP 业务连接 (Sonny ID)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = pipeline.sonnyId,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = ColorInternalTunnel
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "SPP 传输协议 / 压缩比",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val protoText = "icmp".uppercase()
                                val compressRatioText = if (record.transferSize > 0 && pipeline.uploadBytes + pipeline.downloadBytes > 0) {
                                    val wireBytes = pipeline.uploadBytes + pipeline.downloadBytes
                                    val ratio = (wireBytes.toDouble() / record.transferSize.toDouble())
                                    String.format(Locale.getDefault(), "%.1f%% (%d B / %d B)", ratio * 100, wireBytes, record.transferSize)
                                } else if (0 > 0) {
                                    "ZSTD (阈值 ${0}B)"
                                } else {
                                    "未启用压缩"
                                }
                                Text(
                                    text = "$protoText · $compressRatioText",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // 卡片 2: 四层平行物理时间对齐视图 (上层浏览器网络、底层内核代理、Sonny 业务子通道、Main 物理隧道帧流 全部基于同一真实时间轴对齐)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "全链路物理时序统一对齐视图",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "浏览器请求、内核代理运转、Sonny 子通道及 Main 物理隧道包流全部按同一绝对时间轴横向对齐",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))

                        // 缩放模式与倍率
                        var zoomLevel by remember { mutableFloatStateOf(1f) }
                        var cropIdleKeepAlive by remember { mutableStateOf(false) }

                        // 如果开启了聚焦活跃传输期（忽略 Keep-Alive 空闲等待），重新计算该卡片的时间轴范围
                        val activeEffectiveTimelineMs = remember(cropIdleKeepAlive, totalTimelineMs, pipeline, record) {
                            if (!cropIdleKeepAlive) {
                                totalTimelineMs
                            } else {
                                val browserEnd = if (timelineBaseMs > 0L && record.absoluteStartMs > 0L) {
                                    (record.absoluteStartMs - timelineBaseMs + record.duration).coerceAtLeast(10.0)
                                } else {
                                    (record.startTime + record.duration).coerceAtLeast(10.0)
                                }
                                val lastPacketMs = pipeline?.packetEvents?.maxOfOrNull { it.timestampMs } ?: 0L
                                val packetEnd = if (timelineBaseMs > 0L && lastPacketMs > 0L) {
                                    (lastPacketMs - timelineBaseMs).toDouble() + 50.0
                                } else 0.0
                                val pipeActiveEnd = if (pipeline != null && timelineBaseMs > 0L && pipeline.startTimeMs > 0L) {
                                    val activePipeDuration = pipeline.routeMs + pipeline.dnsOrConnectMs + pipeline.handshakeMs + pipeline.waitFirstDataMs + pipeline.dataTransferMs
                                    (pipeline.startTimeMs - timelineBaseMs + activePipeDuration).toDouble() + 50.0
                                } else 0.0

                                val maxActive = maxOf(browserEnd, packetEnd, pipeActiveEnd, 50.0)
                                maxActive.coerceAtMost(totalTimelineMs)
                            }
                        }

                        // 缩放控制栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "缩放:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(1f to "1x", 2f to "2x", 4f to "4x", 8f to "8x").forEach { (scale, label) ->
                                FilterChip(
                                    selected = zoomLevel == scale,
                                    onClick = { zoomLevel = scale },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.height(26.dp)
                                )
                            }

                            Spacer(Modifier.width(4.dp))
                            FilterChip(
                                selected = cropIdleKeepAlive,
                                onClick = { cropIdleKeepAlive = !cropIdleKeepAlive },
                                label = {
                                    Text(
                                        if (cropIdleKeepAlive) "聚焦活跃期: 开" else "聚焦活跃期: 关",
                                        fontSize = 11.sp,
                                        fontWeight = if (cropIdleKeepAlive) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.height(26.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // 可横向滚动的甘特图容器
                        val ganttScrollState = rememberScrollState()
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val baseCardWidth = maxWidth
                            val contentWidth = baseCardWidth * zoomLevel
                            val effectiveTotalMs = activeEffectiveTimelineMs

                            // 计算相对偏移量与比例
                            val browserStartOffsetMs = if (timelineBaseMs > 0L && record.absoluteStartMs > 0L) {
                                (record.absoluteStartMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
                            } else {
                                record.startTime
                            }
                            val browserStartWeight = (browserStartOffsetMs / effectiveTotalMs).coerceIn(0.0, 0.98).toFloat()
                            val browserDurationWeight = (record.duration / effectiveTotalMs).coerceIn(0.005, 1.0).toFloat()

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(ganttScrollState)
                                    .width(contentWidth)
                            ) {
                                // 标尺横线 (Timeline Ruler)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("0ms", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${(effectiveTotalMs * 0.25).toInt()}ms", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${(effectiveTotalMs * 0.5).toInt()}ms", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${(effectiveTotalMs * 0.75).toInt()}ms", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${effectiveTotalMs.toInt()}ms", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                // 序列 0: 预建连与引擎调度阶段 (若有记录或有触发起点)
                                val totalPreOverheadMs = record.dispatchDuration + record.cacheOrPrecheckDuration + record.queueingDuration
                                if (totalPreOverheadMs > 0.0 || (triggerTimeMs > 0L && timelineBaseMs == triggerTimeMs)) {
                                    val triggerOffsetMs = if (triggerTimeMs > 0L && timelineBaseMs > 0L) {
                                        (triggerTimeMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
                                    } else 0.0
                                    val triggerStartWeight = (triggerOffsetMs / effectiveTotalMs).coerceIn(0.0, 0.98).toFloat()
                                    val preDurationSpan = if (totalPreOverheadMs > 0.0) totalPreOverheadMs else (browserStartOffsetMs - triggerOffsetMs).coerceAtLeast(1.0)
                                    val preDurationWeight = (preDurationSpan / effectiveTotalMs).coerceIn(0.005, 1.0).toFloat()

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "0. 预备: 引擎调度/缓存判定/排队准备",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = ColorDispatch,
                                        )
                                        Text(
                                            text = "${preDurationSpan.toInt()} ms",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                            color = ColorDispatch,
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(14.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    ) {
                                        val leftOffset = contentWidth * triggerStartWeight
                                        val barWidth = (contentWidth * preDurationWeight).coerceAtLeast(3.dp)

                                        Row(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .offset(x = leftOffset)
                                                .width(barWidth)
                                        ) {
                                            val d = preDurationSpan
                                            val dispW = (record.dispatchDuration / d).toFloat().coerceIn(0f, 1f)
                                            val cacheW = (record.cacheOrPrecheckDuration / d).toFloat().coerceIn(0f, 1f)
                                            val queueW = (record.queueingDuration / d).toFloat().coerceIn(0f, 1f)

                                            if (dispW > 0) Box(Modifier.fillMaxHeight().weight(dispW).background(ColorDispatch))
                                            if (cacheW > 0) Box(Modifier.fillMaxHeight().weight(cacheW).background(ColorPrecheck))
                                            if (queueW > 0) Box(Modifier.fillMaxHeight().weight(queueW).background(ColorQueueing))

                                            if (dispW <= 0 && cacheW <= 0 && queueW <= 0) {
                                                Box(Modifier.fillMaxSize().background(ColorDispatch.copy(alpha = 0.8f)))
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))
                                }

                                // 序列 1: 浏览器上层条标签 + 甘特条
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "1. 上层: 浏览器网络时序",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ColorDns,
                                    )
                                    Text(
                                        text = "${record.duration.toInt()} ms",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                        color = ColorDns,
                                    )
                                }
                                Spacer(Modifier.height(3.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                ) {
                                    val leftOffset = contentWidth * browserStartWeight
                                    val barWidth = (contentWidth * browserDurationWeight).coerceAtLeast(3.dp)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .offset(x = leftOffset)
                                            .width(barWidth)
                                    ) {
                                        if (record.duration > 0) {
                                            val d = record.duration
                                            val dnsW = (record.dnsDuration / d).toFloat().coerceIn(0f, 1f)
                                            val connW = ((record.connectDuration - record.sslDuration).coerceAtLeast(0.0) / d).toFloat().coerceIn(0f, 1f)
                                            val sslW = (record.sslDuration / d).toFloat().coerceIn(0f, 1f)
                                            val ttfbW = (record.ttfb / d).toFloat().coerceIn(0f, 1f)
                                            val downW = (record.downloadDuration / d).toFloat().coerceIn(0f, 1f)
                                            val accountedW = dnsW + connW + sslW + ttfbW + downW
                                            val otherW = (1f - accountedW).coerceIn(0f, 1f)

                                            if (otherW > 0.05f && (record.cacheOrPrecheckDuration > 0 || record.queueingDuration > 0)) {
                                                Box(Modifier.fillMaxHeight().weight(otherW).background(ColorQueueing))
                                            }
                                            if (dnsW > 0) Box(Modifier.fillMaxHeight().weight(dnsW).background(ColorDns))
                                            if (connW > 0) Box(Modifier.fillMaxHeight().weight(connW).background(ColorConnect))
                                            if (sslW > 0) Box(Modifier.fillMaxHeight().weight(sslW).background(ColorSsl))
                                            if (ttfbW > 0) Box(Modifier.fillMaxHeight().weight(ttfbW).background(ColorTtfb))
                                            if (downW > 0) Box(Modifier.fillMaxHeight().weight(downW).background(ColorDownload))

                                            if (dnsW <= 0 && connW <= 0 && sslW <= 0 && ttfbW <= 0 && downW <= 0 && otherW <= 0.05f) {
                                                Box(Modifier.fillMaxSize().background(ColorOther))
                                            }
                                        } else {
                                            Box(Modifier.fillMaxSize().background(ColorOther))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // 序列 2: 内核底层条标签 + 甘特条 (与上层平行排列)
                                if (pipeline != null && pipeline.totalTime > 0) {
                                    val pipeStartOffsetMs = if (timelineBaseMs > 0L && pipeline.startTimeMs > 0L) {
                                        (pipeline.startTimeMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
                                    } else {
                                        browserStartOffsetMs
                                    }
                                    val pipeStartWeight = (pipeStartOffsetMs / effectiveTotalMs).coerceIn(0.0, 0.98).toFloat()
                                    val effectivePipeTime = if (cropIdleKeepAlive) {
                                        (pipeline.routeMs + pipeline.dnsOrConnectMs + pipeline.handshakeMs + pipeline.waitFirstDataMs + pipeline.dataTransferMs).toDouble().coerceAtLeast(1.0)
                                    } else {
                                        pipeline.totalTime.toDouble()
                                    }
                                    val pipeDurationWeight = (effectivePipeTime / effectiveTotalMs).coerceIn(0.005, 1.0).toFloat()
                                    val d = effectivePipeTime

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "2. 底层: 内核代理运转 (${if (pipeline.isDirect) "DIRECT" else "TUNNEL"})",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = ColorInternalTunnel,
                                        )
                                        Text(
                                            text = "${effectivePipeTime.toInt()} ms",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                            color = ColorInternalTunnel,
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(14.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    ) {
                                        val leftOffset = contentWidth * pipeStartWeight
                                        val barWidth = (contentWidth * pipeDurationWeight).coerceAtLeast(3.dp)

                                        Row(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .offset(x = leftOffset)
                                                .width(barWidth)
                                        ) {
                                            val activeDuration = (pipeline.routeMs + pipeline.dnsOrConnectMs + pipeline.handshakeMs + pipeline.waitFirstDataMs + pipeline.dataTransferMs).toDouble()
                                            val safeD = if (activeDuration > 0.0) maxOf(d, activeDuration) else d

                                            val routeW = (pipeline.routeMs / safeD).toFloat().coerceIn(0f, 1f)
                                            val connW = (pipeline.dnsOrConnectMs / safeD).toFloat().coerceIn(0f, 1f)
                                            val hsW = (pipeline.handshakeMs / safeD).toFloat().coerceIn(0f, 1f)
                                            val waitW = (pipeline.waitFirstDataMs / safeD).toFloat().coerceIn(0f, 1f)
                                            val pipeW = (pipeline.dataTransferMs / safeD).toFloat().coerceIn(0f, 1f)
                                            val accountedW = routeW + connW + hsW + waitW + pipeW
                                            val idleW = (1f - accountedW).coerceIn(0f, 1f)

                                            if (routeW > 0) Box(Modifier.fillMaxHeight().weight(routeW).background(ColorInternalRoute))
                                            if (connW > 0) Box(Modifier.fillMaxHeight().weight(connW).background(ColorInternalTunnel))
                                            if (hsW > 0) Box(Modifier.fillMaxHeight().weight(hsW).background(ColorInternalHandshake))
                                            if (waitW > 0) Box(Modifier.fillMaxHeight().weight(waitW).background(ColorInternalWait))
                                            if (pipeW > 0) Box(Modifier.fillMaxHeight().weight(pipeW).background(ColorInternalPipe))
                                            if (idleW > 0.02f) Box(Modifier.fillMaxHeight().weight(idleW).background(ColorOther.copy(alpha = 0.5f)))

                                            if (routeW <= 0 && connW <= 0 && hsW <= 0 && waitW <= 0 && pipeW <= 0 && idleW <= 0.02f) {
                                                Box(Modifier.fillMaxSize().background(ColorOther))
                                            }
                                        }
                                    }

                                    // 序列 3: Sonny 业务子通道包流 (真实绝对时间对齐)
                                    val sonnyPackets = remember(pipeline.packetEvents) {
                                        pipeline.packetEvents.filter { it.channel == "SONNY" }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "3. 通道: Sonny 子通道包流 (${pipeline.sonnyId})",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF00ACC1),
                                        )
                                        Text(
                                            text = "${sonnyPackets.size} 包",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = Color(0xFF00ACC1),
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    PacketStreamTimelineBar(
                                        packets = sonnyPackets,
                                        timelineBaseMs = timelineBaseMs,
                                        totalTimelineMs = effectiveTotalMs,
                                        containerWidth = contentWidth,
                                    )

                                    // 序列 4: Main 物理隧道复用通道帧流 (真实绝对时间对齐)
                                    val mainPackets = remember(pipeline.packetEvents) {
                                        pipeline.packetEvents.filter { it.channel == "MAIN" }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "4. 通道: Main 物理隧道复用帧流 (Pingtunnel Tunnel Pipe)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFFFF9800),
                                        )
                                        Text(
                                            text = "${mainPackets.size} 帧",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = Color(0xFFFF9800),
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    PacketStreamTimelineBar(
                                        packets = mainPackets,
                                        timelineBaseMs = timelineBaseMs,
                                        totalTimelineMs = effectiveTotalMs,
                                        containerWidth = contentWidth,
                                    )

                                    Spacer(Modifier.height(12.dp))

                                    // 底层通道包类型图例
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FrameTypeDot(ColorFrameOpen, "OPEN (建连)")
                                        FrameTypeDot(ColorFrameOpenRsp, "OPENRSP (就绪)")
                                        FrameTypeDot(ColorFrameDataUp, "DATA (↑上传)")
                                        FrameTypeDot(ColorFrameDataDown, "DATA (↓下载)")
                                        FrameTypeDot(ColorFrameClose, "CLOSE (关闭)")
                                    }
                                } else {
                                    Text(
                                        text = "无对应内核连接记录（可能直接命中浏览器/系统缓存）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                // 卡片 3: 上层浏览器阶段明细列表 (向下可滑动查看)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.diag_browser_section_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))

                        // 发包前的预备开销拆解展示
                        if (record.dispatchDuration > 0) {
                            DetailTimingRow(ColorDispatch, "UI 点击 -> WebKit 引擎调度 / IPC", record.dispatchDuration)
                        }
                        if (record.cacheOrPrecheckDuration > 0) {
                            DetailTimingRow(ColorPrecheck, "本地缓存 / HSTS / 策略比对", record.cacheOrPrecheckDuration)
                        }
                        if (record.queueingDuration > 0) {
                            DetailTimingRow(ColorQueueing, "网络连接池排队 / Socket 准备", record.queueingDuration)
                        }
                        if (record.dispatchDuration > 0 || record.cacheOrPrecheckDuration > 0 || record.queueingDuration > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), thickness = 0.5.dp)
                        }

                        DetailTimingRow(ColorDns, stringResource(R.string.diag_phase_dns), record.dnsDuration)
                        DetailTimingRow(ColorConnect, stringResource(R.string.diag_phase_connect), record.connectDuration)
                        if (record.sslDuration > 0) {
                            DetailTimingRow(ColorSsl, stringResource(R.string.diag_phase_ssl), record.sslDuration, isSub = true)
                        }
                        DetailTimingRow(ColorTtfb, stringResource(R.string.diag_phase_ttfb), record.ttfb)
                        DetailTimingRow(ColorDownload, stringResource(R.string.diag_phase_download), record.downloadDuration)
                    }
                }

                // 卡片 4: 底层内核代理运转明细列表 (向下可滑动查看)
                if (pipeline != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.diag_internal_section_title),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ColorInternalTunnel,
                                )
                                Text(
                                    text = if (pipeline.isDirect) "本地直连 (DIRECT)" else "Pingtunnel 代理隧道 (TUNNEL)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (pipeline.isDirect) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                                )
                            }
                            Spacer(Modifier.height(8.dp))

                            DetailTimingRow(ColorInternalRoute, stringResource(R.string.diag_internal_route), pipeline.routeMs.toDouble())
                            DetailTimingRow(ColorInternalTunnel, if (pipeline.isDirect) "目标 DNS / 本地建连" else stringResource(R.string.diag_internal_tunnel), pipeline.dnsOrConnectMs.toDouble())
                            DetailTimingRow(ColorInternalHandshake, stringResource(R.string.diag_internal_handshake), pipeline.handshakeMs.toDouble())
                            DetailTimingRow(ColorInternalWait, stringResource(R.string.diag_internal_wait), pipeline.waitFirstDataMs.toDouble())
                            DetailTimingRow(ColorInternalPipe, stringResource(R.string.diag_internal_pipe), pipeline.dataTransferMs.toDouble())

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "传输流量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "↑ ${pipeline.uploadBytes} B  ↓ ${pipeline.downloadBytes} B",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }

                            if (!pipeline.errorReason.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "错误原因: ${pipeline.errorReason}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFE53935)),
                                )
                            }
                        }
                    }
                }

                // 卡片 5: 细粒度内核运转时序与异常排查链路 (带绝对精确时间戳)
                if (traces.isNotEmpty()) {
                    val traceTimeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "内核内部事件轨迹 (${traces.size} 条)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            traces.forEachIndexed { index, tr ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val traceColor = if (tr.isError) Color(0xFFE53935) else when (tr.stage) {
                                        "ROUTE" -> ColorInternalRoute
                                        "UPSTREAM", "DIRECT", "DNS" -> ColorInternalTunnel
                                        "HANDSHAKE" -> ColorInternalHandshake
                                        "DATA" -> ColorInternalPipe
                                        else -> ColorOther
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(traceColor)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    // 格式化时间戳 (HH:mm:ss.SSS)
                                    val timeStr = if (tr.timestampMs > 0) traceTimeFormat.format(Date(tr.timestampMs)) else "—"
                                    Text(
                                        text = timeStr,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(76.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = tr.stage,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        fontSize = 10.sp,
                                        color = traceColor,
                                        modifier = Modifier.width(66.dp)
                                    )
                                    Text(
                                        text = tr.detail,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        fontSize = 11.sp,
                                        color = if (tr.isError) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (index < traces.lastIndex) {
                                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PacketStreamTimelineBar(
    packets: List<com.esrrhs.pingtunnel.client.proxy.PacketEvent>,
    timelineBaseMs: Long,
    totalTimelineMs: Double,
    containerWidth: Dp? = null,
) {
    val boxModifier = if (containerWidth != null) {
        Modifier.width(containerWidth)
    } else {
        Modifier.fillMaxWidth()
    }
    BoxWithConstraints(
        modifier = boxModifier
            .height(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        val totalWidth = maxWidth
        if (packets.isNotEmpty() && totalTimelineMs > 0) {
            packets.forEach { pkt ->
                val offsetMs = (pkt.timestampMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
                val fraction = (offsetMs / totalTimelineMs).coerceIn(0.0, 0.98).toFloat()
                val xPos = totalWidth * fraction
                val color = when (pkt.frameType) {
                    "OPEN" -> ColorFrameOpen
                    "OPENRSP" -> ColorFrameOpenRsp
                    "DATA" -> if (pkt.isUpload) ColorFrameDataUp else ColorFrameDataDown
                    "CLOSE" -> ColorFrameClose
                    else -> ColorOther
                }
                Box(
                    modifier = Modifier
                        .offset(x = xPos)
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(color)
                )
            }
        }
    }
}

@Composable
private fun FrameTypeDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}

@Composable
private fun DetailTimingRow(color: Color, label: String, durationMs: Double, isSub: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = if (isSub) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (durationMs > 0) "${durationMs.toInt()} ms" else "—",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (durationMs > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

private data class ProgressSegment(
    val name: String,
    val durationMs: Double,
    val color: Color,
)

@Composable
private fun DetailProgressBar(
    items: List<ProgressSegment>,
    totalDuration: Double,
) {
    val validItems = items.filter { it.durationMs > 0.0 }
    val safeTotal = if (totalDuration > 0.0) totalDuration else validItems.sumOf { it.durationMs }.coerceAtLeast(1.0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            if (validItems.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    validItems.forEach { seg ->
                        val weight = (seg.durationMs / safeTotal).toFloat().coerceIn(0.01f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(weight)
                                .background(seg.color)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ColorOther.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    timingBridge: BrowserTimingBridge,
    onProgressChanged: (Int) -> Unit,
    onLoadingStateChanged: (Boolean, String?) -> Unit,
): WebView {
    // 如果 VPN 处于连接状态，通过 AndroidX WebKit ProxyController 把 WebView 的流量
    // 显式桥接到本地 SOCKS5 代理端口（因为本 App 自身被排除在 VPN 外以防 VPN 回环，
    // WebView 默认走物理网卡直连，必须通过 ProxyOverride 接入隧道）
    val socksPort = ActiveSession.socksPort
    if (socksPort != null && socksPort > 0 && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
        runCatching {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks://127.0.0.1:$socksPort")
                .addDirect() // 当代理异常或未连接时回退直连
                .build()
            val executor = Executor { it.run() }
            ProxyController.getInstance().setProxyOverride(proxyConfig, executor) {
                timingBridge.postLog("INFO", "WebView proxy override set to socks://127.0.0.1:$socksPort")
            }
        }.onFailure {
            timingBridge.postLog("WARN", "Set proxy override failed: ${it.message}")
        }
    }

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE // 测速与诊断时避免使用本地强缓存，看真实网络耗时
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 添加 JS 交互 Bridge
        addJavascriptInterface(timingBridge, BrowserTimingBridge.JS_INTERFACE_NAME)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(newProgress)
                // 在进度达到 50% 以上时即尝试注入收集，尽早抓取导航时序
                if (newProgress > 50) {
                    view?.evaluateJavascript(BrowserTimingBridge.INJECTION_SCRIPT, null)
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadingStateChanged(true, url)
                // 页面开始加载即注入脚本
                view?.evaluateJavascript(BrowserTimingBridge.INJECTION_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLoadingStateChanged(false, url)
                // 页面完成时再次触发采集
                view?.evaluateJavascript(BrowserTimingBridge.INJECTION_SCRIPT, null)
                view?.evaluateJavascript("if (window.__collectTiming) window.__collectTiming();", null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val desc = error?.description?.toString() ?: "Unknown error"
                timingBridge.postLog("ERR", "Resource error (${request?.url}): $desc")
            }
        }
    }
}

private fun sanitizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.contains(".") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
