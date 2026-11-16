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
    private var lastHeartbeatMs = 0L
    private var lastPublishMs = 0L
    private var lastBridgeRestartMs = 0L
    private var watchdogJob: Job? = null
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

        if (savedConfig.autoStartOnLaunch || savedConfig.onecConnectionString.isNotBlank()) {
            startBackgroundSync(activePath, activeUrl, savedConfig.onecHttpUrl, 3)
        }
        startWatchdog()
    }

    /**
     * Сторож: раз в 30 секунд проверяет, что цикл слежения жив.
     * Если он почему-то остановился, а служба числится активной — поднимает его заново,
     * чтобы обновление не прекращалось молча.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                val st = _state.value
                if (st.isRunning && syncJob?.isActive != true) {
                    addLog("♻️ Фоновая служба остановилась — запускаем заново", isError = true)
                    startBackgroundSync(st.monitoredPath, st.webAppUrl, st.onecHttpUrl, st.intervalSeconds)
                }
            }
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
        // если фоновая служба была остановлена — запускаем её, чтобы обновление шло само
        if (conn.isNotBlank() && !_state.value.isRunning) {
            startBackgroundSync(_state.value.monitoredPath, _state.value.webAppUrl, _state.value.onecHttpUrl, 3)
        }
    }

    fun stopBackgroundSync() {
        configStorage.saveConfig(configStorage.loadConfig().copy(autoStartOnLaunch = false))
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
        comBridge.ensureRunning(conn, ONEC_POLL_SECONDS, configStorage.loadConfig().onecReportVariant)
        val res = comBridge.read() ?: return Result.success("Ждём данные из 1С")

        // Если 1С давно не присылала свежие данные (процесс завис) — перезапускаем чтение
        val ageSec = res.ts?.let {
            runCatching { java.time.Duration.between(java.time.LocalDateTime.parse(it), java.time.LocalDateTime.now()).seconds }.getOrNull()
        }
        if (ageSec != null && ageSec > ONEC_POLL_SECONDS * 4 + 60 &&
            System.currentTimeMillis() - lastBridgeRestartMs > 5 * 60_000
        ) {
            lastBridgeRestartMs = System.currentTimeMillis()
            addLog("♻️ 1С не присылает новые данные уже ${ageSec / 60} мин — перезапускаем подключение", isError = true)
            comBridge.restart()
            return Result.success("Перезапуск подключения к 1С")
        }
        if (!res.ok) {
            val err = res.error ?: "неизвестная ошибка"
            if (comBridge.switchBitnessIfNeeded(err)) return Result.success("Пробуем другую разрядность COM")
            logOnecErrorOnce("1С: $err")
            return Result.failure(Exception(err))
        }
        loggedErrors.clear()

        val checksum = CRC32().also { it.update(res.rows.toString().toByteArray(Charsets.UTF_8)) }.value
        // даже если изменений не видно, раз в FORCED_REFRESH_MINUTES минут обновляем таблицу принудительно,
        // чтобы таблица не могла «застрять» из-за сбоя в определении изменений
        val refreshDue = System.currentTimeMillis() - lastPublishMs > FORCED_REFRESH_MINUTES * 60_000
        if (!force && checksum == lastFileChecksum && !refreshDue) {
            // раз в минуту показываем в логе, что слежение живо
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatMs > 60_000) {
                lastHeartbeatMs = now
                val total = String.format(Locale.US, "%,.0f", res.rows.sumOf { it.dolg }).replace(',', ' ')
                addLog("✅ 1С проверена (данные на ${res.ts?.substringAfter('T') ?: "—"}): строк ${res.rows.size}, общий долг $total — изменений нет. Последняя выгрузка: ${_state.value.lastSyncTime}")
            }
            return Result.success("1С без изменений")
        }
        lastHeartbeatMs = System.currentTimeMillis()

        addLog("🔎 1С: ${comBridge.diagnostics(res)}")
        res.warn?.let { logOnecErrorOnce("1С: $it") }
        val rows = comBridge.toReportRows(res.rows)
        if (rows.isEmpty()) {
            lastPublishMs = System.currentTimeMillis()
            lastFileChecksum = checksum
            logOnecErrorOnce("Отчёт «Дебиторка» вернул 0 строк — пришлите строку 🔎 из журнала")
            return Result.failure(Exception("0 строк"))
        }
        addLog("🔔 Данные из 1С на ${res.ts ?: "сейчас"}: ${rows.size} строк. Обновляем «Импорт»...")
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


        addLog("🚀 Отправка 1С на проверку недостающих записей в Google Sheets...")
        val reportDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        val importRows = excelImportService.readRawImportRows(targetFile)
        if (importRows.isEmpty() && importResult.shipments.isEmpty()) {
            addLog("⚠️ Нет данных для отправки в Google Sheets")
            return Result.success("0 записей")
        }
        val publishResult = googleSyncService.sendBatchToGoogleSheets(webAppUrl, importResult, importRows, reportDate)

        val timeStr = SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault()).format(Date())

        return publishResult.onSuccess { details ->
            lastFileChecksum = currentChecksum
            lastPublishMs = System.currentTimeMillis()
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

    /** Строка в журнал приложения снаружи (например, о состоянии автозапуска). */
    fun note(message: String, isError: Boolean = false) = addLog(message, isError)

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

/** Через сколько минут таблица обновляется принудительно, даже если изменений не видно. */
private const val FORCED_REFRESH_MINUTES = 5L

/** Как часто приложение заново опрашивает 1С (секунды). */
private const val ONEC_POLL_SECONDS = 300
