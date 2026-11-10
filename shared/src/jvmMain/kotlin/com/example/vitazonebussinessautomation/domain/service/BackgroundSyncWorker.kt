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
    val onecConnectionString: String = "",
    val webAppUrl: String = "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec",
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
    private val loggedErrors = mutableSetOf<String>()
    private var lastDiscoveredPath: String = ""
    private var lastPublishError: String = ""
    private var lastSalesConvention: Boolean? = null
    private val comBridge = OnecComBridge { addLog(it) }

    private fun logOnecErrorOnce(msg: String) {
        if (loggedErrors.add(msg)) {
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
        val defaultPath = ""  // пусто = авто-поиск файла «дебиторка» в Документах/Рабочем столе/Загрузках
        val activePath = savedConfig.monitoredPath.ifBlank { defaultPath }
        val defaultUrl = "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec"
        val activeUrl = savedConfig.webAppUrl.ifBlank { defaultUrl }

        _state.value = _state.value.copy(
            monitoredPath = activePath,
            webAppUrl = activeUrl,
            onecHttpUrl = savedConfig.onecHttpUrl,
            onecConnectionString = savedConfig.onecConnectionString,
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

        val activeUrl = webAppUrl.ifBlank { "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec" }

        // copy(): не терять остальные настройки (в том числе подключение к 1С)
        val configToSave = configStorage.loadConfig().copy(
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
            safeSyncCheck(force = true)

            // STEP 2: CONTINUOUS WATCHER FOR ACCOUNTANT ACTIONS IN 1C
            while (isActive) {
                delay(intervalSeconds * 1000L)
                val r = safeSyncCheck(force = false)
                // при ошибке (нет файла, Google не отвечает) не долбим каждые 3 секунды
                if (r.isFailure) delay(27_000L)
            }
        }
    }

    /** Сохранить строку подключения к 1С (COM) и сразу переподключиться. Пусто = отключить COM. */
    fun setOnecConnection(connectionString: String) {
        val conn = connectionString.trim()
        configStorage.saveConfig(configStorage.loadConfig().copy(onecConnectionString = conn))
        _state.value = _state.value.copy(onecConnectionString = conn)
        comBridge.stop()
        lastFileChecksum = 0L
        loggedErrors.clear()
        addLog(if (conn.isBlank()) "🔌 Прямое подключение к 1С отключено" else "🔌 Подключение к 1С сохранено, данные появятся в течение минуты")
    }

    fun stopBackgroundSync() {
        comBridge.stop()
        syncJob?.cancel()
        syncJob = null
        _state.value = _state.value.copy(isRunning = false)
        addLog("🔴 Служба наблюдения за 1С остановлена")
    }

    suspend fun syncNow(): Result<String> {
        return safeSyncCheck(force = true)
    }

    /** Любой сбой (в том числе java.lang.Error) логируется, а цикл слежки продолжает работать. */
    private suspend fun safeSyncCheck(force: Boolean): Result<String> = try {
        performSyncCheck(force)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logOnecErrorOnce("Сбой синхронизации: ${t::class.simpleName}: ${t.message}")
        Result.failure(t)
    }

    private suspend fun performSyncCheck(force: Boolean = false): Result<String> {
        val path = _state.value.monitoredPath.trim()
        val url = _state.value.webAppUrl.trim().ifBlank { "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec" }
        val onecApi = _state.value.onecHttpUrl.trim()

        if (url.isBlank()) {
            val err = "Укажите Google Apps Script Web App URL"
            addLog("❌ $err", isError = true)
            return Result.failure(Exception(err))
        }

        // 0. Прямое подключение к базе 1С (COM) — основной режим: данные всегда актуальные
        val onecConn = _state.value.onecConnectionString.trim()
        if (onecConn.isNotBlank()) {
            return checkViaCom(onecConn, url, force)
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
                    loggedErrors.clear()
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
            val err = if (path.isBlank()) "Файл дебиторки 1С не найден (укажите путь в приложении)"
                      else "Файл выгрузки 1С не найден по пути: $path"
            logOnecErrorOnce(err)
            return Result.failure(Exception(err))
        }

        loggedErrors.clear()
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

    private suspend fun checkViaCom(conn: String, url: String, force: Boolean): Result<String> {
        if (!comBridge.isWindows) {
            logOnecErrorOnce("Прямое подключение к 1С (COM) работает только на Windows")
            return Result.failure(Exception("COM только на Windows"))
        }
        comBridge.ensureRunning(conn, 60)
        val res = comBridge.read() ?: return Result.success("Ждём данные из 1С")
        if (!res.ok) {
            val err = res.error ?: "неизвестная ошибка"
            if (comBridge.switchBitnessIfNeeded(err)) return Result.success("Пробуем другую разрядность COM")
            logOnecErrorOnce("1С: $err")
            return Result.failure(Exception(err))
        }
        loggedErrors.clear()

        val checksum = CRC32().also { it.update(res.rows.toString().toByteArray(Charsets.UTF_8)) }.value
        if (!force && checksum == lastFileChecksum) return Result.success("1С без изменений")

        val (rows, salesAreOut) = comBridge.toReportRows(res.rows)
        if (lastSalesConvention != salesAreOut) {
            lastSalesConvention = salesAreOut
            addLog("ℹ️ В регистре «Дебиторка» продажи записаны как «${if (salesAreOut) "Расход" else "Приход"}» — знак долга учтён")
        }
        addLog("🔔 Данные из базы 1С на ${res.ts ?: "сейчас"}: ${rows.size} договоров с долгом. Обновляем «Импорт»...")
        val report = comBridge.writeReportFile(rows)
        return try {
            processAndPublishFile(report, url, checksum)
        } finally {
            report.delete()
        }
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
        val importRows = excelImportService.readRawImportRows(targetFile)
        val publishResult = googleSyncService.sendBatchToGoogleSheets(webAppUrl, importResult, importRows, reportDate)

        val timeStr = SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault()).format(Date())

        return publishResult.onSuccess { details ->
            lastFileChecksum = currentChecksum
            lastPublishError = ""
            _state.value = _state.value.copy(
                lastSyncTime = timeStr,
                lastPublishedShipments = importResult.shipments.size,
                lastPublishedPayments = importResult.payments.size
            )
            addLog("✅ Сверка и выгрузка завершена! Недостающие накладные занесены в Google Sheets. ($details)")
        }.onFailure { err ->
            val msg = "Ошибка выгрузки в Google Sheets: ${err.message}"
            if (msg != lastPublishError) {
                lastPublishError = msg
                addLog("❌ $msg", isError = true)
            }
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
                    if (found.absolutePath != lastDiscoveredPath) {
                        lastDiscoveredPath = found.absolutePath
                        addLog("🔍 Авто-обнаружен файл дебиторки 1С: ${found.absolutePath}")
                    }
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
