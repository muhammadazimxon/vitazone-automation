package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.data.AppRepository
import com.example.vitazonebussinessautomation.domain.service.SyncWorkerHolder
import com.example.vitazonebussinessautomation.domain.service.SyncLogEntry
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun ExcelImportScreen(
    repository: AppRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val syncWorker = SyncWorkerHolder.worker
    val syncState by syncWorker.state.collectAsState()

    var filePathInput by remember { mutableStateOf(syncState.monitoredPath) }
    var webAppUrlInput by remember { mutableStateOf(syncState.webAppUrl) }
    var onecApiInput by remember { mutableStateOf(syncState.onecHttpUrl) }
    var selectedIntervalSeconds by remember { mutableStateOf(syncState.intervalSeconds) }

    var isManualSyncing by remember { mutableStateOf(false) }
    var showAppScriptDialog by remember { mutableStateOf(false) }

    // Keep fields updated if state changes
    LaunchedEffect(syncState.monitoredPath, syncState.webAppUrl, syncState.onecHttpUrl) {
        if (filePathInput.isBlank()) filePathInput = syncState.monitoredPath
        if (webAppUrlInput.isBlank()) webAppUrlInput = syncState.webAppUrl
        if (onecApiInput.isBlank()) onecApiInput = syncState.onecHttpUrl
    }

    val isFilePresent = remember(filePathInput) {
        if (filePathInput.isBlank()) false
        else {
            val f = File(filePathInput)
            f.exists()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning Banner if file/path is not found
        if (!isFilePresent) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.errorContainer),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                "Внимание: Файл выгрузки 1С не найден или путь не указан!",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Укажите путь к файлу или папке выгрузки из 1С на компьютере бухгалтера, чтобы система начала автоматическое отслеживание.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val chooser = JFileChooser()
                            chooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                            chooser.fileFilter = FileNameExtensionFilter("Excel 1C (*.xls, *.xlsx)", "xls", "xlsx")
                            val ret = chooser.showOpenDialog(null)
                            if (ret == JFileChooser.APPROVE_OPTION) {
                                val selectedPath = chooser.selectedFile.absolutePath
                                filePathInput = selectedPath
                                syncWorker.startBackgroundSync(selectedPath, webAppUrlInput, onecApiInput, selectedIntervalSeconds)
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Указать путь 1С", fontSize = 13.sp)
                    }
                }
            }
        }

        // Main Background Sync Config Card
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Авто-Служба выгрузки 1С в фоновом режиме",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (syncState.isRunning && isFilePresent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (syncState.isRunning && isFilePresent) "🟢 ФОНОВАЯ СЛУЖБА АКТИВНА" else "⚪ В ОЖИДАНИИ ПУТИ 1С",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = if (syncState.isRunning && isFilePresent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Программа отслеживает 1С в реальном времени и автоматически заносит в Google Sheets отсутствующие накладные без дубликатов.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showAppScriptDialog = true },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Скрипт Google Apps", fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                // Target Web App URL Input
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = webAppUrlInput,
                        onValueChange = {
                            webAppUrlInput = it
                            if (syncState.isRunning) {
                                syncWorker.startBackgroundSync(filePathInput, it, onecApiInput, selectedIntervalSeconds)
                            }
                        },
                        placeholder = { Text("Google Apps Script Web App URL (https://script.google.com/macros/s/.../exec)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                }

                // 1C Monitored File/Folder Path Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = filePathInput,
                        onValueChange = {
                            filePathInput = it
                            if (syncState.isRunning) {
                                syncWorker.startBackgroundSync(it, webAppUrlInput, onecApiInput, selectedIntervalSeconds)
                            }
                        },
                        placeholder = { Text("Введите путь к файлу/папке выгрузки из 1С (например, C:\\1C_Export\\)...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )

                    Button(
                        onClick = {
                            val chooser = JFileChooser()
                            chooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                            chooser.fileFilter = FileNameExtensionFilter("Excel 1C (*.xls, *.xlsx)", "xls", "xlsx")
                            val ret = chooser.showOpenDialog(null)
                            if (ret == JFileChooser.APPROVE_OPTION) {
                                val selectedPath = chooser.selectedFile.absolutePath
                                filePathInput = selectedPath
                                syncWorker.startBackgroundSync(selectedPath, webAppUrlInput, onecApiInput, selectedIntervalSeconds)
                            }
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Указать путь 1С", fontSize = 13.sp)
                    }
                }

                // Optional 1C HTTP REST API URL Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = onecApiInput,
                        onValueChange = {
                            onecApiInput = it
                            if (syncState.isRunning) {
                                syncWorker.startBackgroundSync(filePathInput, webAppUrlInput, it, selectedIntervalSeconds)
                            }
                        },
                        placeholder = { Text("Опционально: URL HTTP-сервиса 1С (например, http://localhost:8080/vita/hs/sverka/debt)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                }

                // Background Sync Controls & Interval
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Проверка изменений 1С:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        listOf(3, 5, 10, 30).forEach { secs ->
                            FilterChip(
                                selected = selectedIntervalSeconds == secs,
                                onClick = {
                                    selectedIntervalSeconds = secs
                                    if (syncState.isRunning) {
                                        syncWorker.startBackgroundSync(filePathInput, webAppUrlInput, onecApiInput, secs)
                                    }
                                },
                                label = { Text("Каждые $secs сек", fontSize = 12.sp) },
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!syncState.isRunning) {
                            Button(
                                onClick = {
                                    syncWorker.startBackgroundSync(filePathInput, webAppUrlInput, onecApiInput, selectedIntervalSeconds)
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Запустить фоновую службу", fontSize = 13.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { syncWorker.stopBackgroundSync() },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Остановить фоновую службу", fontSize = 13.sp)
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isManualSyncing = true
                                    syncWorker.syncNow()
                                    isManualSyncing = false
                                }
                            },
                            enabled = !isManualSyncing,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            if (isManualSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(6.dp))
                                Text("Выгрузка...", fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("🚀 Выгрузить из 1С сейчас", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Summary Metric Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("Статус фоновой службы", if (syncState.isRunning && isFilePresent) "АКТИВНА" else "ОЖИДАЕТ ПУТЬ 1С", Modifier.weight(1f))
            MetricCard("Последняя авто-выгрузка", syncState.lastSyncTime, Modifier.weight(1.2f))
            MetricCard("Занесено отгрузок", "${syncState.lastPublishedShipments}", Modifier.weight(1f))
            MetricCard("Занесено оплат", "${syncState.lastPublishedPayments}", Modifier.weight(1f))
        }

        // Real-time Event Logs Table
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Table Header
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Время", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("События слежения за 1С Бухгалтерией и Google Sheets", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val logsList = syncState.logs

                if (logsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Фоновая служба активна и отслеживает действия бухгалтера в 1С",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(logsList) { _, log: SyncLogEntry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(log.timestamp, modifier = Modifier.width(90.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = log.message,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 12.sp,
                                    color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }

    if (showAppScriptDialog) {
        AppsScriptCodeDialog(onDismiss = { showAppScriptDialog = false })
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AppsScriptCodeDialog(onDismiss: () -> Unit) {
    val scriptCode = """
// ===== СКРИПТ ДЛЯ GOOGLE SHEETS (ПРОВЕРКА И ДОБАВЛЕНИЕ НЕДОСТАЮЩИХ 1С СТРОК) =====
function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.tryLock(30000);
  try {
    var raw = e && e.postData ? e.postData.contents : "{}";
    var data = JSON.parse(raw);
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (!ss) {
      ss = SpreadsheetApp.openById("1MvCed9GxkmG9Jah30lwwce8yZKA0GzUCbJ7DWZCMVkV");
    }

    var addedShipments = 0;
    var addedPayments = 0;

    // 1. Лист "Отгрузка" - проверяем, каких номеров документов из 1С НЕТ в Google Таблице
    var shipSheet = ss.getSheetByName("Отгрузка");
    if (!shipSheet) { shipSheet = ss.insertSheet("Отгрузка"); }
    var shipments = data.shipments || data.rows || [];

    if (shipments.length > 0) {
      var shipLastRow = shipSheet.getLastRow();
      var existingShipDocs = {};
      
      if (shipLastRow >= 3) {
        var existingDocsRange = shipSheet.getRange(3, 7, shipLastRow - 2, 1).getValues(); // Col G: № док
        for (var k = 0; k < existingDocsRange.length; k++) {
          var docStr = String(existingDocsRange[k][0]).trim();
          if (docStr) { existingShipDocs[docStr] = true; }
        }
      }

      var shipLastInsertRow = Math.max(3, shipLastRow + 1);
      var shipMatrix = [];

      for (var i = 0; i < shipments.length; i++) {
        var r = shipments[i];
        var dNo = String(r.docNumber || "").trim();

        // Если этого документа из 1С еще НЕТ в Google Таблице -> добавляем!
        if (!dNo || !existingShipDocs[dNo]) {
          shipMatrix.push([
            r.date || "",
            1,
            "",
            r.inn || "",
            r.companyName || "",
            dNo,
            "",
            r.amount !== undefined ? r.amount : "",
            r.status || "",
            r.manager || "",
            r.variant || ""
          ]);
          if (dNo) existingShipDocs[dNo] = true;
          addedShipments++;
        }
      }

      if (shipMatrix.length > 0) {
        shipSheet.getRange(shipLastInsertRow, 2, shipMatrix.length, 11).setValues(shipMatrix);
      }
    }

    // 2. Лист "Поступления" - проверяем, каких оплат из 1С НЕТ в Google Таблице
    var payments = data.payments || [];
    if (payments.length > 0) {
      var paySheet = ss.getSheetByName("Поступления");
      if (!paySheet) { paySheet = ss.insertSheet("Поступления"); }
      var payLastRow = paySheet.getLastRow();
      var existingPayDocs = {};

      if (payLastRow >= 3) {
        var existingPayDocsRange = paySheet.getRange(3, 1, payLastRow - 2, 1).getValues(); // Col A: № док
        for (var pIdx = 0; pIdx < existingPayDocsRange.length; pIdx++) {
          var pDocStr = String(existingPayDocsRange[pIdx][0]).trim();
          if (pDocStr) { existingPayDocs[pDocStr] = true; }
        }
      }

      var payLastInsertRow = Math.max(3, payLastRow + 1);
      var payMatrix = [];

      for (var j = 0; j < payments.length; j++) {
        var p = payments[j];
        var payDNo = String(p.docNumber || "").trim();

        // Если этой оплаты из 1С еще НЕТ в Google Таблице -> добавляем!
        if (!payDNo || !existingPayDocs[payDNo]) {
          payMatrix.push([
            payDNo,
            p.date || "",
            p.inn || "",
            p.companyName || "",
            p.amount !== undefined ? p.amount : "",
            p.status || "Оплачено",
            p.note || "",
            p.variant || ""
          ]);
          if (payDNo) existingPayDocs[payDNo] = true;
          addedPayments++;
        }
      }

      if (payMatrix.length > 0) {
        paySheet.getRange(payLastInsertRow, 1, payMatrix.length, 8).setValues(payMatrix);
      }
    }

    return ContentService.createTextOutput(JSON.stringify({
      success: true, 
      added: addedShipments,
      addedPayments: addedPayments
    })).setMimeType(ContentService.MimeType.JSON);

  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({success: false, error: err.toString()}))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}
""".trimIndent()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Код Google Apps Script (Добавление только недостающих записей)") },
        text = {
            Column(modifier = Modifier.width(540.dp)) {
                Text(
                    "Обновленный код doPost(). Он сверяет 1С с Google Sheets и добавляет ТОЛЬКО те накладные и оплаты из 1С, которых еще нет в вашей Google Таблице (без дубликатов!).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = scriptCode,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    readOnly = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss() }, shape = RoundedCornerShape(6.dp)) {
                Text("Закрыть")
            }
        }
    )
}
