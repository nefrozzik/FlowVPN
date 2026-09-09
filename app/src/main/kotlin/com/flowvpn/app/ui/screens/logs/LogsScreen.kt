package com.flowvpn.app.ui.screens.logs

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowvpn.core.logger.LogEntry
import com.flowvpn.core.logger.LogLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBackClick: () -> Unit,
    viewModel: LogsViewModel = viewModel(),
) {
    val logs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val crashReport by viewModel.crashReport.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Автопрокрутка вниз только если пользователь уже находится внизу списка
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            val isAtBottom = !listState.canScrollForward || listState.firstVisibleItemIndex >= (logs.size - 6).coerceAtLeast(0)
            if (isAtBottom) {
                listState.scrollToItem(logs.size - 1)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Логи приложения и ядра", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            com.flowvpn.app.util.LogExportHelper.exportAndShareLogs(context)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Экспорт логов")
                    }
                    IconButton(
                        onClick = {
                            val text = viewModel.getAllLogsText()
                            if (text.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(text))
                                scope.launch { snackbarHostState.showSnackbar("Логи скопированы в буфер") }
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать")
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Баннер обнаружения падения приложения (Crash Report)
            if (!crashReport.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Обнаружен отчет о сбое приложения",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = crashReport!!.lines().take(6).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                com.flowvpn.app.util.LogExportHelper.exportAndShareLogs(context)
                            }) {
                                Text("Экспорт отчета", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = {
                                viewModel.clearCrashReport()
                            }) {
                                Text("Скрыть")
                            }
                        }
                    }
                }
            }
            // Поле поиска
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Поиск в логах...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Фильтры по уровням
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedLevel == null,
                    onClick = { viewModel.setLevel(null) },
                    label = { Text("Все (${logs.size})") }
                )
                FilterChip(
                    selected = selectedLevel == LogLevel.ERROR,
                    onClick = { viewModel.setLevel(if (selectedLevel == LogLevel.ERROR) null else LogLevel.ERROR) },
                    label = { Text("Errors", color = Color(0xFFE53935)) }
                )
                FilterChip(
                    selected = selectedLevel == LogLevel.WARN,
                    onClick = { viewModel.setLevel(if (selectedLevel == LogLevel.WARN) null else LogLevel.WARN) },
                    label = { Text("Warnings", color = Color(0xFFFB8C00)) }
                )
                FilterChip(
                    selected = selectedLevel == LogLevel.INFO,
                    onClick = { viewModel.setLevel(if (selectedLevel == LogLevel.INFO) null else LogLevel.INFO) },
                    label = { Text("Info", color = Color(0xFF1E88E5)) }
                )
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Логов пока нет.\nЛоги ядра sing-box будут отображаться здесь при подключении.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs, key = { it.timestamp.toString() + "_" + it.hashCode() }) { entry ->
                            LogLineItem(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFE53935)
        LogLevel.WARN -> Color(0xFFFB8C00)
        LogLevel.INFO -> Color(0xFF1E88E5)
        LogLevel.DEBUG -> Color(0xFF757575)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.formattedTime,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(85.dp)
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(levelColor.copy(alpha = 0.2f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = entry.level.name,
                color = levelColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            lineHeight = 16.sp
        )
    }
}
