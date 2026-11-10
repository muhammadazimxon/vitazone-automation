package com.example.vitazonebussinessautomation.domain.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@Serializable
data class OnecComRow(
    val mp: String = "",
    val doc: String = "",
    val date: String = "",
    val kontr: String = "",
    val note: String = "",
    val inAll: Double = 0.0,
    val outAll: Double = 0.0,
    val inSale: Double = 0.0,
    val outSale: Double = 0.0,
    val inFin: Double = 0.0,
    val outFin: Double = 0.0,
    val inSaleFin: Double = 0.0,
    val outSaleFin: Double = 0.0,
    val cnt: Int = 0
)

@Serializable
data class OnecComResult(
    val ok: Boolean = false,
    val ts: String? = null,
    val error: String? = null,
    val rows: List<OnecComRow> = emptyList()
)

/** Строка отчёта в терминах листа «Импорт»: Долг = Приход − Продажа (долг отрицательный). */
data class DebtReportRow(
    val mp: String, val doc: String, val date: String, val kontr: String, val note: String,
    val paid: Double, val sale: Double, val debt: Double
)

/**
 * Прямое чтение дебиторки из базы 1С через COM.
 * Запускает фоновый PowerShell (скрыто), который держит соединение с базой и раз в минуту
 * пишет остатки регистра «Дебиторка» в debt.json. Приложение читает этот файл.
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
    private var currentInterval = 60

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    private var lastStartMs = 0L

    /** Запускает (или перезапускает, если упал/сменились настройки) фоновый процесс чтения 1С. */
    @Synchronized
    fun ensureRunning(conn: String, intervalSec: Int) {
        if (!isWindows) return
        val p = process
        val sameSettings = conn == currentConn && intervalSec == currentInterval
        if (p != null && p.isAlive && sameSettings) return
        // упавший процесс перезапускаем не чаще раза в 30 секунд
        if (sameSettings && System.currentTimeMillis() - lastStartMs < 30_000) return
        if (conn != currentConn) { use32 = false; triedBoth = false }
        currentConn = conn
        currentInterval = intervalSec
        start()
    }

    private fun start() {
        stop()
        lastStartMs = System.currentTimeMillis()
        val text = javaClass.getResourceAsStream("/onec_debt.ps1")?.readBytes()?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("onec_debt.ps1 не найден в ресурсах приложения")
        // BOM обязателен: без него Windows PowerShell 5.1 читает кириллицу запроса неверно
        script.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        connFile.writeText(currentConn, Charsets.UTF_8)
        outFile.delete()

        val winDir = System.getenv("WINDIR") ?: "C:\\Windows"
        val ps = File(winDir, if (use32) "SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe" else "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        process = ProcessBuilder(
            ps.absolutePath, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-File", script.absolutePath,
            "-ConnFile", connFile.absolutePath,
            "-OutFile", outFile.absolutePath,
            "-IntervalSec", currentInterval.toString(),
            "-ParentPid", ProcessHandle.current().pid().toString()
        ).redirectErrorStream(true).redirectOutput(File(dir, "bridge.log")).start()
        log("🔌 Подключаемся к базе 1С (${if (use32) "32" else "64"}-битный COM)...")
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
        start()
        return true
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

    /**
     * Перевод сумм регистра в колонки отчёта. Знак определяется по самим данным:
     * документы «Продажа» увеличивают долг, поэтому их вид движения показывает, как в регистре записан долг.
     * Закрытые договоры (долг = 0) не выводятся, как и в отчёте 1С.
     */
    fun toReportRows(rows: List<OnecComRow>, useFin: Boolean = false): Pair<List<DebtReportRow>, Boolean> {
        // выбранный ресурс регистра: «Задолженность сум» или «Сум финанс»
        fun inA(r: OnecComRow) = if (useFin) r.inFin else r.inAll
        fun outA(r: OnecComRow) = if (useFin) r.outFin else r.outAll
        fun inS(r: OnecComRow) = if (useFin) r.inSaleFin else r.inSale
        fun outS(r: OnecComRow) = if (useFin) r.outSaleFin else r.outSale

        val salesAreOut = rows.sumOf { outS(it) } >= rows.sumOf { inS(it) }
        val out = rows.map { r ->
            val sale = if (salesAreOut) outS(r) - inS(r) else inS(r) - outS(r)
            val debt = if (salesAreOut) inA(r) - outA(r) else outA(r) - inA(r)
            DebtReportRow(r.mp, r.doc, r.date, r.kontr, r.note, paid = debt + sale, sale = sale, debt = debt)
        }.filter { abs(it.debt) >= 0.005 }
        return out to salesAreOut
    }

    /** Сводка для лога: сколько записей и сумм по каждому ресурсу пришло из регистра. */
    fun diagnostics(rows: List<OnecComRow>): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%,.0f", v).replace(',', ' ')
        return "записей в регистре: ${rows.sumOf { it.cnt }}, групп: ${rows.size}; " +
            "«Задолженность сум»: приход ${f(rows.sumOf { it.inAll })}, расход ${f(rows.sumOf { it.outAll })}; " +
            "«Сум финанс»: приход ${f(rows.sumOf { it.inFin })}, расход ${f(rows.sumOf { it.outFin })}"
    }

    /** Пишет строки в xlsx того же вида, что отчёт 1С «Дебиторка» (Приход / Продажа / Долг). */
    fun writeReportFile(rows: List<DebtReportRow>): File {
        val f = File.createTempFile("1c_com_", ".xlsx")
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("TDSheet")
            val header = listOf("МП", "Док номер", "Дата составления", "Тип / Контрагент", "Док номер.Статус", "Приход", "Продажа", "Долг")
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
