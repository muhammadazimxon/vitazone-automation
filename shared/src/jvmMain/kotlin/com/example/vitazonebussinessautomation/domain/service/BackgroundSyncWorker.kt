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

        addLog("🟢 Запущена фоновая служба: данные читаются напрямую из базы 1С раз в минуту")

        syncJob = scope.launch {
            // STEP 1: INITIAL IMMEDIATE FULL SYNC ON STARTUP
            addLog("⚡ Первая выгрузка из базы 1С при запуске...")
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
        val url = _state.value.webAppUrl.trim().ifBlank { "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec" }

        if (url.isBlank()) {
            val err = "Укажите Google Apps Script Web App URL"
            addLog("❌ $err", isError = true)
            return Result.failure(Exception(err))
        }

        // Единственный источник данных — сама база 1С (COM). Выгруженные файлы больше не читаются.
        val onecConn = _state.value.onecConnectionString.trim()
        if (onecConn.isBlank()) {
            logOnecErrorOnce("Не указана база 1С: укажите папку базы в приложении и нажмите «Подключить 1С»")
            return Result.failure(Exception("Не указана база 1С"))
        }
        return checkViaCom(onecConn, url, force)
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
