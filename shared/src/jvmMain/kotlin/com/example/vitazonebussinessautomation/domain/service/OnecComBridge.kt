package com.example.vitazonebussinessautomation.domain.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Строка отчёта «Дебиторка» как её отдала 1С — ничего не пересчитывается. */
@Serializable
data class OnecComRow(
    val mp: String = "",
    val doc: String = "",
    val date: String = "",
    val kontr: String = "",
    val note: String = "",
    val prihod: Double = 0.0,   // колонка отчёта «Приход»
    val rashod: Double = 0.0,   // колонка отчёта «Расход»
    val dolg: Double = 0.0      // колонка отчёта «На конец»
)

@Serializable
data class OnecComResult(
    val ok: Boolean = false,
    val ts: String? = null,
    val error: String? = null,
    val warn: String? = null,
    val ver: String = "",
    val source: String = "",            // report — сам отчёт, register — запасной запрос
    val variant: String = "",
    val variants: List<String> = emptyList(),
    val columns: List<String> = emptyList(),
    val rows: List<OnecComRow> = emptyList()
)

/** Строка для листа «Импорт»: Приход | Продажа | Долг. */
data class DebtReportRow(
    val mp: String, val doc: String, val date: String, val kontr: String, val note: String,
    val paid: Double, val sale: Double, val debt: Double
)

/**
 * Чтение дебиторки из базы 1С через COM.
 *
 * Фоновый PowerShell запускает сам отчёт «Дебиторка» и пишет его результат в debt.json;
 * приложение читает этот файл. Каждая проверка — новый запуск со свежим подключением к базе,
 * иначе 1С может отдавать данные, устаревшие на момент подключения.
 */
class OnecComBridge(private val log: (String) -> Unit) {
    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val dir = File(System.getProperty("user.home"), ".vitazone_automation/1c").apply { mkdirs() }
    private val script = File(dir, "onec_debt.ps1")
    private val connFile = File(dir, "conn.txt")
    private val outFile = File(dir, "debt.json")
    private val json = Json { ignoreUnknownKeys = true }

    private var process: Process? = null
    private var use32 = false
    private var triedBoth = false
    private var currentConn = ""
    private var currentInterval = 300
    private var currentVariant = ""
    private var lastStartMs = 0L
    private var announced = false

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /** Запускает чтение 1С, если прошлый запуск закончился или устарел. */
    @Synchronized
    fun ensureRunning(conn: String, intervalSec: Int, variant: String) {
        if (!isWindows) return
        val p = process
        val same = conn == currentConn && intervalSec == currentInterval && variant == currentVariant
        val sinceStart = System.currentTimeMillis() - lastStartMs
        // чтение ещё идёт — не трогаем. Убиваем, только если висит втрое дольше интервала:
        // большой отчёт может считаться дольше 5 минут, иначе его убивало бы на каждом круге.
        if (p != null && p.isAlive && same && sinceStart < intervalSec * 3000L) return
        // следующий запуск — не раньше, чем через intervalSec
        if (same && sinceStart < intervalSec * 1000L) return
        if (conn != currentConn) { use32 = false; triedBoth = false; announced = false; outFile.delete() }
        currentConn = conn
        currentInterval = intervalSec
        currentVariant = variant
        start()
    }

    private fun start() {
        stop()
        lastStartMs = System.currentTimeMillis()
        val text = javaClass.getResourceAsStream("/onec_debt.ps1")?.readBytes()?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("onec_debt.ps1 не найден в ресурсах приложения")
        // BOM обязателен: без него Windows PowerShell 5.1 читает кириллицу неверно
        script.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        connFile.writeText(currentConn, Charsets.UTF_8)
        // старый результат удаляем: иначе приложение покажет данные или ошибку прошлого запуска,
        // в том числе от предыдущей версии программы
        outFile.delete()

        val winDir = System.getenv("WINDIR") ?: "C:\\Windows"
        val ps = File(
            winDir,
            if (use32) "SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe"
            else "System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        )
        process = ProcessBuilder(
            ps.absolutePath, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-File", script.absolutePath,
            "-ConnFile", connFile.absolutePath,
            "-OutFile", outFile.absolutePath,
            "-IntervalSec", currentInterval.toString(),
            "-ParentPid", ProcessHandle.current().pid().toString(),
            "-Variant", currentVariant
        ).redirectErrorStream(true).redirectOutput(File(dir, "bridge.log")).start()
        if (!announced) {
            announced = true
            log("🔌 Подключаемся к базе 1С (${if (use32) "32" else "64"}-битный COM)...")
        }
    }

    /** COM-класс не зарегистрирован для этой разрядности — один раз пробуем другую. */
    @Synchronized
    fun switchBitnessIfNeeded(error: String): Boolean {
        val notRegistered = error.contains("80040154") ||
            error.contains("не зарегистрирован", ignoreCase = true) ||
            error.contains("not registered", ignoreCase = true)
        if (!notRegistered || triedBoth) return false
        triedBoth = true
        use32 = !use32
        announced = false
        outFile.delete()
        start()
        return true
    }

    /** Принудительный перезапуск чтения (например, если процесс завис). */
    @Synchronized
    fun restart() {
        if (currentConn.isNotBlank()) start()
    }

    @Synchronized
    fun stop() {
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null
    }

    fun read(): OnecComResult? {
        if (!outFile.exists()) return null
        return try {
            val text = outFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            json.decodeFromString(OnecComResult.serializer(), text)
        } catch (e: Exception) {
            null // файл в процессе записи — прочитаем на следующей проверке
        }
    }

    /** Строки отчёта как есть: Приход, Расход («Продажа»), На конец («Долг»). */
    fun toReportRows(rows: List<OnecComRow>): List<DebtReportRow> =
        rows.map { DebtReportRow(it.mp, it.doc, it.date, it.kontr, it.note, it.prihod, it.rashod, it.dolg) }

    /** Короткая сводка для журнала приложения. */
    fun diagnostics(res: OnecComResult): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%,.0f", v).replace(',', ' ')
        val src = if (res.source == "report") "отчёт «Дебиторка»" else "регистр (запасной путь)"
        val variant = if (res.variant.isNotBlank()) ", вариант «${res.variant}»" else ""
        return "скрипт v${res.ver.ifBlank { "?" }}; источник: $src$variant; строк ${res.rows.size}; " +
            "приход ${f(res.rows.sumOf { it.prihod })}, расход ${f(res.rows.sumOf { it.rashod })}, " +
            "долг ${f(res.rows.sumOf { it.dolg })}" +
            (if (res.variants.isNotEmpty()) ". Варианты отчёта: ${res.variants.joinToString(", ")}" else "")
    }

    /** Пишет строки в xlsx того же вида, что лист «Импорт» (Приход / Продажа / Долг). */
    fun writeReportFile(rows: List<DebtReportRow>): File {
        val f = File.createTempFile("1c_com_", ".xlsx")
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("TDSheet")
            val header = listOf(
                "МП", "Док номер", "Дата составления", "Тип / Контрагент", "Док номер.Статус",
                "Приход", "Продажа", "Долг"
            )
            sh.createRow(0).let { h -> header.forEachIndexed { i, v -> h.createCell(i).setCellValue(v) } }
            rows.forEachIndexed { idx, r ->
                val row = sh.createRow(idx + 1)
                listOf(r.mp, r.doc, r.date, r.kontr, r.note).forEachIndexed { i, v -> row.createCell(i).setCellValue(v) }
                if (abs(r.paid) >= 0.005) row.createCell(5).setCellValue(r.paid)
                if (abs(r.sale) >= 0.005) row.createCell(6).setCellValue(r.sale)
                row.createCell(7).setCellValue(r.debt)
            }
            FileOutputStream(f).use { wb.write(it) }
        }
        return f
    }
}
