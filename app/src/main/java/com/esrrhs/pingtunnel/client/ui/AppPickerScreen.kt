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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.esrrhs.pingtunnel.client.R
import com.esrrhs.pingtunnel.client.data.InstalledApp
import com.esrrhs.pingtunnel.client.data.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App 勾选器：返回用户勾选的包名集合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    initialSelected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    // 系统 BACK 返回编辑器（不带走当前勾选，与顶栏返回一致）
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var allApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelected) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) { InstalledApps.list(context) }
    }

    val filtered = if (query.isBlank()) allApps
    else allApps.filter {
        it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_picker_title, selected.size)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { onConfirm(selected) }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.app_picker_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.label)
                            Text(
                                text = app.packageName,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = selected.contains(app.packageName),
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app.packageName
                                else selected - app.packageName
                            },
                        )
                    }
                }
            }
        }
    }
}
