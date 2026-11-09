package com.example.vitazonebussinessautomation.domain.service

import com.example.vitazonebussinessautomation.data.AppConfig
import com.example.vitazonebussinessautomation.data.AppConfigStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

data class SyncLogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)

data class BackgroundSyncState(
    val isRunning: Boolean = false,
    val monitoredPath: String = "",
    val onecHttpUrl: String = "",
    val webAppUrl: String = "https://script.google.com/macros/s/AKfycbyX3Ap2t7fNkahgYp0xTUJ3na-Zi0Z9uvT7u7Qm3mO3M3IjUu5zwBE1BcRTpbBKPHdY7A/exec",
    val intervalSeconds: Int = 3,
    val lastSyncTime: String = "—",
    val lastPublishedShipments: Int = 0,
    val lastPublishedPayments: Int = 0,
    val logs: List<SyncLogEntry> = emptyList()
)

class BackgroundSyncWorker(
    private val excelImportService: ExcelImportService = ExcelImportService(),
    private val googleSyncService: GoogleSheetsSyncService = GoogleSheetsSyncService(),
    private val configStorage: AppConfigStorage = AppConfigStorage()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    private val _state = MutableStateFlow(BackgroundSyncState())
    val state: StateFlow<BackgroundSyncState> = _state.asStateFlow()

    private var lastFileChecksum: Long = 0L
    private var lastOnecError: String = ""

    private fun logOnecErrorOnce(msg: String) {
        if (msg != lastOnecError) {
            lastOnecError = msg
            addLog("⚠️ $msg", isError = true)
        }
    }

    /** Контрольная сумма по СОДЕРЖИМОМУ файла: пересохранение без изменений данных не триггерит выгрузку. */
    private fun checksumOf(file: File): Long = try {
        CRC32().also { it.update(file.readBytes()) }.value
    } catch (e: Exception) {
        file.lastModified() + file.length()
    }

    init {
        val savedConfig = configStorage.loadConfig()
        val defaultPath = "/Users/macbookpro/AndroidStudioProjects/VitazoneBussinessAutomation/дебиторка 25.10.2026.xls"
        val activePath = savedConfig.monitoredPath.ifBlank { defaultPath }
        val defaultUrl = "https://script.google.com/macros/s/AKfycbyX3Ap2t7fNkahgYp0xTUJ3na-Zi0Z9uvT7u7Qm3mO3M3IjUu5zwBE1BcRTpbBKPHdY7A/exec"
        val activeUrl = savedConfig.webAppUrl.ifBlank { defaultUrl }

        _state.value = _state.value.copy(
            monitoredPath = activePath,
            webAppUrl = activeUrl,
            onecHttpUrl = savedConfig.onecHttpUrl,
            intervalSeconds = 3
        )

        if (savedConfig.autoStartOnLaunch) {
            startBackgroundSync(activePath, activeUrl, savedConfig.onecHttpUrl, 3)
        }
    }

    fun startBackgroundSync(
        filePathOrFolder: String,
        webAppUrl: String,
        onecHttpUrl: String = "",
        intervalSeconds: Int = 3
    ) {
        if (_state.value.isRunning) {
            stopBackgroundSync()
        }

        val activeUrl = webAppUrl.ifBlank { "https://script.google.com/macros/s/AKfycbyX3Ap2t7fNkahgYp0xTUJ3na-Zi0Z9uvT7u7Qm3mO3M3IjUu5zwBE1BcRTpbBKPHdY7A/exec" }

        val configToSave = AppConfig(
            monitoredPath = filePathOrFolder,
            webAppUrl = activeUrl,
            onecHttpUrl = onecHttpUrl,
            autoStartOnLaunch = true,
            checkIntervalSeconds = intervalSeconds
        )
        configStorage.saveConfig(configToSave)

        _state.value = _state.value.copy(
            isRunning = true,
            monitoredPath = filePathOrFolder,
            webAppUrl = activeUrl,
            onecHttpUrl = onecHttpUrl,
            intervalSeconds = intervalSeconds
        )

        addLog("🟢 Запущена фоновая служба слежения за 1С (проверка каждые $intervalSeconds сек.)")

        syncJob = scope.launch {
            // STEP 1: INITIAL IMMEDIATE FULL SYNC ON STARTUP
            addLog("⚡ ПЕРВАЯ ВЫГРУЗКА ПРИ ЗАПУСКЕ: Проверка недостающих накладных 1С в Google Sheets...")
            performSyncCheck(force = true)

            // STEP 2: CONTINUOUS WATCHER FOR ACCOUNTANT ACTIONS IN 1C
            while (isActive) {
                delay(intervalSeconds * 1000L)
                performSyncCheck(force = false)
            }
        }
    }

    fun stopBackgroundSync() {
        syncJob?.cancel()
        syncJob = null
        _state.value = _state.value.copy(isRunning = false)
        addLog("🔴 Служба наблюдения за 1С остановлена")
    }

    suspend fun syncNow(): Result<String> {
        return performSyncCheck(force = true)
    }

    private suspend fun performSyncCheck(force: Boolean = false): Result<String> {
        val path = _state.value.monitoredPath.trim()
        val url = _state.value.webAppUrl.trim().ifBlank { "https://script.google.com/macros/s/AKfycbyX3Ap2t7fNkahgYp0xTUJ3na-Zi0Z9uvT7u7Qm3mO3M3IjUu5zwBE1BcRTpbBKPHdY7A/exec" }
        val onecApi = _state.value.onecHttpUrl.trim()

        if (path.isBlank() && onecApi.isBlank()) {
            val err = "Укажите путь к выгрузке 1С"
            addLog("❌ $err", isError = true)
            return Result.failure(Exception(err))
        }

        if (url.isBlank()) {
            val err = "Укажите Google Apps Script Web App URL"
            addLog("❌ $err", isError = true)
            return Result.failure(Exception(err))
        }

        // 1. Try 1C HTTP REST API if specified
        if (onecApi.isNotBlank()) {
            try {
                val conn = URL(onecApi).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val code = conn.responseCode
                if (code in 200..299) {
                    val bytes = conn.inputStream.readBytes()
                    lastOnecError = ""
                    val currentChecksum = CRC32().also { it.update(bytes) }.value
                    if (!force && currentChecksum == lastFileChecksum) {
                        return Result.success("1С без изменений")
                    }
                    val tempFile = File.createTempFile("1c_debt_", ".xls")
                    tempFile.writeBytes(bytes)
                    addLog("🔔 Дебиторка в 1С изменилась! Обновляем лист «Импорт» в Google Sheets...")
                    return try {
                        processAndPublishFile(tempFile, url, currentChecksum)
                    } finally {
                        tempFile.delete()
                    }
                } else {
                    logOnecErrorOnce("1С HTTP вернул код $code")
                }
            } catch (e: Exception) {
                logOnecErrorOnce("1С HTTP недоступен: ${e.message}")
                // Fallback to local file
            }
        }

        // 2. Real-time Local File/Folder Monitoring
        val targetFile = resolveTargetFile(path) ?: autoDiscoverOnecFile()
        if (targetFile == null || !targetFile.exists()) {
            val err = "Файл выгрузки 1С не найден по пути: $path"
            addLog("⚠️ $err", isError = true)
            return Result.failure(Exception(err))
        }

        val currentChecksum = checksumOf(targetFile)
        if (!force && currentChecksum == lastFileChecksum) {
            return Result.success("Файл 1С без изменений")
        }

        addLog("🔔 Изменения в 1С (${targetFile.name})! Сверяем с Google Sheets и заносим отсутствующие накладные...")

        val safeTempFile = try {
            val temp = File.createTempFile("1c_watch_", ".${targetFile.extension}")
            targetFile.inputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            temp
        } catch (e: Exception) {
            targetFile
        }

        val result = processAndPublishFile(safeTempFile, url, currentChecksum)
        if (safeTempFile != targetFile && safeTempFile.exists()) {
            safeTempFile.delete()
        }
        return result
    }

    private suspend fun processAndPublishFile(
        targetFile: File,
        webAppUrl: String,
        currentChecksum: Long
    ): Result<String> {
        val importResult = excelImportService.parseDebtorExcel(targetFile)

        if (importResult.errors.isNotEmpty()) {
            val errStr = importResult.errors.joinToString("; ")
            addLog("❌ Ошибка чтения данных 1С: $errStr", isError = true)
            return Result.failure(Exception(errStr))
        }

        if (importResult.shipments.isEmpty()) {
            addLog("⚠️ В выгрузке 1С не найдено записей отгрузок.")
            return Result.success("0 записей")
        }

        addLog("🚀 Отправка 1С на проверку недостающих записей в Google Sheets...")
        val reportDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        val importRows = excelImportService.readRawImportRows(targetFile, reportDate)
        val publishResult = googleSyncService.sendBatchToGoogleSheets(webAppUrl, importResult, importRows)

        val timeStr = SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault()).format(Date())

        return publishResult.onSuccess { details ->
            lastFileChecksum = currentChecksum
            _state.value = _state.value.copy(
                lastSyncTime = timeStr,
                lastPublishedShipments = importResult.shipments.size,
                lastPublishedPayments = importResult.payments.size
            )
            addLog("✅ Сверка и выгрузка завершена! Недостающие накладные занесены в Google Sheets. ($details)")
        }.onFailure { err ->
            addLog("❌ Ошибка выгрузки в Google Sheets: ${err.message}", isError = true)
        }
    }

    private fun resolveTargetFile(path: String): File? {
        if (path.isBlank()) return null
        val f = File(path)
        if (f.isFile) return f
        if (f.isDirectory) {
            val excelFiles = f.listFiles { file ->
                file.isFile && (file.extension.equals("xls", ignoreCase = true) || file.extension.equals("xlsx", ignoreCase = true))
            }
            return excelFiles?.maxByOrNull { it.lastModified() }
        }
        return null
    }

    private fun autoDiscoverOnecFile(): File? {
        val userHome = System.getProperty("user.home") ?: "."
        val candidateDirs = listOf(
            File(userHome, "Documents"),
            File(userHome, "Desktop"),
            File(userHome, "Downloads"),
            File(userHome, "AppData/Local/Temp"),
            File(userHome, "AppData/Local/1C"),
            File("C:/1C"),
            File("C:/1C_Export"),
            File(".")
        )

        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                val found = dir.listFiles { f ->
                    f.isFile && (f.extension.equals("xls", ignoreCase = true) || f.extension.equals("xlsx", ignoreCase = true)) &&
                            (f.name.contains("дебитор", ignoreCase = true) || f.name.contains("долг", ignoreCase = true) || f.name.contains("взаиморасчет", ignoreCase = true))
                }?.maxByOrNull { it.lastModified() }

                if (found != null) {
                    addLog("🔍 Авто-обнаружен файл дебиторки 1С: ${found.absolutePath}")
                    return found
                }
            }
        }
        return null
    }

    private fun addLog(message: String, isError: Boolean = false) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = SyncLogEntry(timeStr, message, isError)
        val currentLogs = _state.value.logs.toMutableList()
        currentLogs.add(0, newEntry)
        if (currentLogs.size > 100) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        _state.value = _state.value.copy(logs = currentLogs)
    }
}

/** Один воркер на всё приложение: стартует при запуске и живёт, пока приложение в трее. */
object SyncWorkerHolder {
    val worker: BackgroundSyncWorker by lazy { BackgroundSyncWorker() }
}
