package com.flowvpn.app.ui.screens.subscriptions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowvpn.core.model.SubscriptionInfo
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    viewModel: SubscriptionsViewModel = viewModel(),
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showQrOptionDialog by remember { mutableStateOf(false) }
    var subscriptionToDelete by remember { mutableStateOf<SubscriptionInfo?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    fun processScannedContent(content: String) {
        val trimmed = content.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            viewModel.addSubscription(trimmed)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Добавление подписки по ссылке...")
            }
        } else {
            viewModel.importFromText(trimmed) { count ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        if (count > 0) "Импортировано серверов: $count" else "Не удалось распознать серверы"
                    )
                }
            }
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents?.trim()
        if (!text.isNullOrBlank()) {
            processScannedContent(text)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val text = decodeQrFromUri(context, uri)?.trim()
            if (!text.isNullOrBlank()) {
                processScannedContent(text)
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("QR-код на изображении не найден")
                }
            }
        }
    }

    fun launchCameraScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Наведите камеру на QR-код подписки")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        qrScannerLauncher.launch(options)
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Подписки", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.updateAll() },
                        enabled = !isLoading && subscriptions.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить все")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = { showQrOptionDialog = true },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать QR-код")
                }

                FloatingActionButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text ?: ""
                        if (clipText.isNotBlank()) {
                            viewModel.importFromText(clipText) { count ->
                                // результат импорта
                            }
                        } else {
                            showImportDialog = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Импорт из буфера")
                }

                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить подписку")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (subscriptions.isEmpty()) {
                EmptySubscriptionsPlaceholder(
                    onScanQrClick = { showQrOptionDialog = true },
                    onAddClick = { showAddDialog = true },
                    onPasteClick = {
                        val clipText = clipboardManager.getText()?.text ?: ""
                        if (clipText.isNotBlank()) {
                            viewModel.importFromText(clipText) {}
                        } else {
                            showImportDialog = true
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(subscriptions, key = { it.id }) { sub ->
                        SubscriptionCard(
                            subscription = sub,
                            onUpdate = { viewModel.updateSubscription(sub.id) },
                            onDelete = { subscriptionToDelete = sub },
                            isUpdating = isLoading
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    // Диалог выбора источника QR-кода
    if (showQrOptionDialog) {
        AlertDialog(
            onDismissRequest = { showQrOptionDialog = false },
            title = { Text("Сканировать QR-код") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Отсканируйте QR-код с URL подписки или конфигурацией сервера (VLESS, VMess, ShadowSocks и др.):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            showQrOptionDialog = false
                            launchCameraScan()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сканировать камерой")
                    }
                    OutlinedButton(
                        onClick = {
                            showQrOptionDialog = false
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать из галереи")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQrOptionDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Диалог добавления по URL
    if (showAddDialog) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить подписку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL подписки (http / https)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = {
                                showAddDialog = false
                                showQrOptionDialog = true
                            }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать QR")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Имя профиля (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            viewModel.addSubscription(url, name.takeIf { it.isNotBlank() })
                            showAddDialog = false
                        }
                    },
                    enabled = url.isNotBlank()
                ) {
                    Text("Скачать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Диалог ручной вставки текста конфигурации
    if (showImportDialog) {
        var textContent by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Импорт ссылок") },
            text = {
                Column {
                    Text(
                        "Вставьте список ссылок (vless://, vmess://, ss://, hysteria2://) или Base64/Clash профиль:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        label = { Text("Конфигурация") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (textContent.isNotBlank()) {
                            viewModel.importFromText(textContent) {}
                            showImportDialog = false
                        }
                    },
                    enabled = textContent.isNotBlank()
                ) {
                    Text("Импортировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Подтверждение удаления
    subscriptionToDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { subscriptionToDelete = null },
            title = { Text("Удалить подписку?") },
            text = { Text("Вы действительно хотите удалить подписку \"${sub.name}\" и все её серверы (${sub.servers.size} шт.)?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSubscription(sub.id)
                        subscriptionToDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { subscriptionToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionInfo,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    isUpdating: Boolean
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subscription.url.isNotBlank()) {
                        Text(
                            text = subscription.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row {
                    if (subscription.url.isNotBlank()) {
                        IconButton(onClick = onUpdate, enabled = !isUpdating) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Трафик и квота
            subscription.usagePercent?.let { usage ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val usedStr = SubscriptionsViewModel.formatBytes((subscription.uploadBytes ?: 0L) + (subscription.downloadBytes ?: 0L))
                        val totalStr = SubscriptionsViewModel.formatBytes(subscription.totalBytes)
                        Text(
                            text = "Трафик: $usedStr / $totalStr",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = String.format("%.0f%%", usage),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (usage > 90f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (usage / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (usage > 90f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Метаданные (срок действия, количество узлов)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text("${subscription.servers.size} серверов", fontSize = 12.sp) }
                )

                if (subscription.isExpired) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Истекла",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = "До: ${SubscriptionsViewModel.formatExpiry(subscription.expireTimestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySubscriptionsPlaceholder(
    onScanQrClick: () -> Unit,
    onAddClick: () -> Unit,
    onPasteClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Нет добавленных подписок",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Добавьте URL подписки вашего провайдера, отсканируйте QR-код или вставьте ссылки из буфера обмена",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onScanQrClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("QR-код", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onAddClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("URL", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onPasteClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Буфер", fontSize = 13.sp)
            }
        }
    }
}

private fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        MultiFormatReader().decode(binaryBitmap).text
    } catch (e: Exception) {
        null
    }
}

private fun decodeQrFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            decodeQrFromBitmap(bitmap)
        }
    } catch (e: Exception) {
        null
    }
}

