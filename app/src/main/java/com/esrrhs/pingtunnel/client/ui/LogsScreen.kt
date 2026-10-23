package com.esrrhs.pingtunnel.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esrrhs.pingtunnel.client.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class LogSource(val title: String, val fileName: String) {
    SPP("SPP", "pingtunnel.log"),
    HEV("hev", "hev.log"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    // 系统 BACK 回到主界面，与顶栏返回一致
    BackHandler(onBack = onBack)
    var source by remember { mutableStateOf(LogSource.SPP) }
    var content by remember { mutableStateOf("") }
    val context = LocalContext.current

    val emptyHint = stringResource(R.string.logs_empty)
    val historyMarker = stringResource(R.string.logs_history_marker)
    LaunchedEffect(source) {
        while (true) {
            content = withContext(Dispatchers.IO) {
                readLog(context.filesDir, source.fileName, historyMarker)
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_logs)) },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                LogSource.entries.forEach { item ->
                    FilterChip(
                        selected = source == item,
                        onClick = { source = item },
                        label = { Text(item.title) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(
                text = content.ifBlank { emptyHint },
                style = typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 读取日志文件尾部；pingtunnel.log 附带滚动的 .1 历史段。 */
private fun readLog(filesDir: File, fileName: String, historyMarker: String): String {
    val main = File(filesDir, fileName)
    val rotated = File(filesDir, "$fileName.1")
    return buildString {
        if (rotated.exists()) {
            append(tailOf(rotated, MAX_CHARS / 2))
            appendLine(historyMarker)
        }
        append(tailOf(main, MAX_CHARS / 2))
    }.trimEnd()
}

private fun tailOf(file: File, maxChars: Int): String =
    runCatching {
        val text = file.readText()
        if (text.length <= maxChars) text else text.substring(text.length - maxChars)
    }.getOrDefault("")

private const val MAX_CHARS = 20_000
private const val REFRESH_INTERVAL_MS = 2_000L
