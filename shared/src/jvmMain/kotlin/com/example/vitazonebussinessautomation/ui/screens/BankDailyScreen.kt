package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.domain.models.BankStatementItem
import com.example.vitazonebussinessautomation.domain.service.BankDailyGoogleSheetsSyncService
import com.example.vitazonebussinessautomation.domain.service.BankDailyImportResult
import com.example.vitazonebussinessautomation.domain.service.BankDailyImportService
import com.example.vitazonebussinessautomation.ui.util.FormatUtils
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun BankDailyScreen(
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val bankService = remember { BankDailyImportService() }
    val googleSyncService = remember { BankDailyGoogleSheetsSyncService() }

    val defaultBankFile = "/Users/macbookpro/AndroidStudioProjects/VitazoneBussinessAutomation/f5b7b194f9002846671963a4ce4cba6b_1792756750.xlsx"
    var filePathInput by remember { mutableStateOf(defaultBankFile) }
    var webAppUrlInput by remember { mutableStateOf("") }

    var importResult by remember { mutableStateOf<BankDailyImportResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var showAppScriptDialog by remember { mutableStateOf(false) }

    // Initial load for default file
    LaunchedEffect(Unit) {
        val f = File(defaultBankFile)
        if (f.exists()) {
            isLoading = true
            importResult = bankService.parseBankStatementExcel(f)
            isLoading = false
            syncStatusMessage = "Автоматически загружена банковская выписка: ${importResult?.totalRows ?: 0} записей"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header Card
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                "Банковская выписка (ежедневно)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Импорт суточных банковских выписок (.xlsx) и публикация поступлений в Google Sheets.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val res = importResult
                                if (res == null || res.items.isEmpty()) {
                                    syncStatusMessage = "Нет данных выписки для копирования!"
                                    return@OutlinedButton
                                }
                                val tsvBuilder = StringBuilder()
                                res.items.forEach { item ->
                                    val dateStr = "${item.date.dayOfMonth.toString().padStart(2, '0')}.${item.date.monthNumber.toString().padStart(2, '0')}.${item.date.year}"
                                    tsvBuilder.append("${item.docNumber}\t$dateStr\t${item.inn}\t${item.companyName}\t${item.credit}\tОплачено\t${item.details}\t${item.variant}\n")
                                }
                                val selection = StringSelection(tsvBuilder.toString())
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                syncStatusMessage = "📋 Выписка (${res.items.size} записей) скопирована в буфер обмена!"
                            },
                            enabled = (importResult?.totalRows ?: 0) > 0,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("📋 Скопировать выписку", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { showAppScriptDialog = true },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Скрипт Выписки", fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                // File Selector Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = filePathInput,
                        onValueChange = { filePathInput = it },
                        placeholder = { Text("Выберите файл банковской выписки...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        readOnly = true,
                        shape = RoundedCornerShape(6.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )

                    Button(
                        onClick = {
                            val chooser = JFileChooser()
                            chooser.fileFilter = FileNameExtensionFilter("Excel Выписка (*.xlsx, *.xls)", "xlsx", "xls")
                            val ret = chooser.showOpenDialog(null)
                            if (ret == JFileChooser.APPROVE_OPTION) {
                                val selectedFile = chooser.selectedFile
                                filePathInput = selectedFile.absolutePath
                                isLoading = true
                                importResult = bankService.parseBankStatementExcel(selectedFile)
                                isLoading = false
                                syncStatusMessage = "Успешно загружено записей выписки: ${importResult?.totalRows ?: 0}"
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Выбрать выписку", fontSize = 13.sp)
                    }
                }

                // Google Web App URL & Publish Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = webAppUrlInput,
                        onValueChange = { webAppUrlInput = it },
                        placeholder = { Text("Google Apps Script Web App URL (https://script.google.com/macros/s/.../exec)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )

                    Button(
                        onClick = {
                            val res = importResult
                            if (res == null || res.items.isEmpty()) {
                                syncStatusMessage = "Сначала выберите и загрузите выписку!"
                                return@Button
                            }
                            if (webAppUrlInput.isBlank()) {
                                syncStatusMessage = "Укажите Web App URL скрипта!"
                                return@Button
                            }

                            scope.launch {
                                isSyncing = true
                                syncStatusMessage = "Публикация выписки в Google Таблицу..."
                                val result = googleSyncService.sendBankStatementToGoogleSheets(webAppUrlInput, res)
                                isSyncing = false
                                result.onSuccess { details ->
                                    syncStatusMessage = "✅ Успешно опубликована выписка! ($details)"
                                }.onFailure { err ->
                                    syncStatusMessage = "❌ Ошибка публикации выписки: ${err.message}"
                                }
                            }
                        },
                        enabled = !isSyncing && (importResult?.totalRows ?: 0) > 0,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text("Публикация...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("🚀 Опубликовать выписку", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (syncStatusMessage != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = syncStatusMessage!!,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Summary Metric Cards
        val res = importResult
        if (res != null && res.totalRows > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard("Записей в выписке", "${res.totalRows}", Modifier.weight(1f))
                MetricCard("Поступления (Кредит)", FormatUtils.formatCurrency(res.totalCredit), Modifier.weight(1.3f))
                MetricCard("Списания (Дебет)", FormatUtils.formatCurrency(res.totalDebit), Modifier.weight(1.3f))
            }
        }

        // Data Preview Table
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
                        Text("#", modifier = Modifier.width(36.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Дата", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("№ Док.", modifier = Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("ИНН", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Корреспондент", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Кредит (Приход)", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Дебет (Расход)", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val itemsList = res?.items ?: emptyList()

                if (itemsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isLoading) "Чтение банковской выписки..." else "Выберите файл банковской выписки для просмотра",
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
                        itemsIndexed(itemsList, key = { _, item -> item.id }) { idx, item: BankStatementItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${idx + 1}", modifier = Modifier.width(36.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                Text(FormatUtils.formatDate(item.date), modifier = Modifier.weight(1f), fontSize = 12.sp)

                                Text(item.docNumber.ifBlank { "—" }, modifier = Modifier.weight(0.9f), fontSize = 12.sp)

                                Text(item.inn.ifBlank { "—" }, modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 12.sp)

                                Text(item.companyName.ifBlank { "—" }, modifier = Modifier.weight(2f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                                Text(
                                    FormatUtils.formatCurrency(item.credit),
                                    modifier = Modifier.weight(1.3f),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )

                                Text(
                                    FormatUtils.formatCurrency(item.debit),
                                    modifier = Modifier.weight(1.3f),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        BankDailyScriptDialog(onDismiss = { showAppScriptDialog = false })
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
private fun BankDailyScriptDialog(onDismiss: () -> Unit) {
    val scriptCode = """
// ===== СКРИПТ ДЛЯ БАНКОВСКОЙ ВЫПИСКИ (ПОСТУПЛЕНИЯ) =====
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

    var items = data.bankStatementItems || [];
    var added = 0;

    if (items.length > 0) {
      var paySheet = ss.getSheetByName("Поступления");
      if (!paySheet) { paySheet = ss.insertSheet("Поступления"); }
      var payLastRow = Math.max(3, paySheet.getLastRow() + 1);
      var payMatrix = [];

      for (var j = 0; j < items.length; j++) {
        var p = items[j];
        if (p.credit && p.credit > 0) {
          payMatrix.push([
            p.docNumber || "",
            p.date || "",
            p.inn || "",
            p.companyName || "",
            p.credit,
            "Оплачено",
            p.details || "",
            p.variant || "Вариант 1"
          ]);
          added++;
        }
      }

      if (payMatrix.length > 0) {
        paySheet.getRange(payLastRow, 1, payMatrix.length, 8).setValues(payMatrix);
      }
    }

    return ContentService.createTextOutput(JSON.stringify({
      success: true, 
      added: added
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
        title = { Text("Код Google Apps Script для Банковской Выписки") },
        text = {
            Column(modifier = Modifier.width(540.dp)) {
                Text(
                    "Этот скрипт заносит банковские поступления на лист 'Поступления' пакетным методом.",
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
