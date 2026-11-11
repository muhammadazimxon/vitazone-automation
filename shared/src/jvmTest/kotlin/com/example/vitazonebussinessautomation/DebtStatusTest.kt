package com.example.vitazonebussinessautomation

import com.example.vitazonebussinessautomation.domain.models.ShipmentStatus
import com.example.vitazonebussinessautomation.domain.service.ExcelImportService
import com.example.vitazonebussinessautomation.domain.service.OnecComBridge
import com.example.vitazonebussinessautomation.domain.service.OnecComRow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * В отчёте 1С остаток = оплачено − отгрузка, то есть ДОЛГ ОТРИЦАТЕЛЬНЫЙ.
 * Этот тест фиксирует правильные статусы, чтобы знак не перепутали снова.
 */
class DebtStatusTest {

    private fun report(rows: List<List<String>>): File {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("r")
        val header = listOf("МП", "Док номер", "Дата составления", "ИНН", "Контрагент", "Назначение", "Сум", "Сум", "Сум")
        (listOf(header) + rows).forEachIndexed { i, cells ->
            val row = sheet.createRow(i)
            cells.forEachIndexed { c, v -> row.createCell(c).setCellValue(v) }
        }
        val f = File.createTempFile("debt_test_", ".xls")
        FileOutputStream(f).use { wb.write(it) }
        wb.close()
        return f
    }

    private fun r(doc: String, paid: String, ship: String, debt: String) =
        listOf("Менеджер", doc, "27.08.2026", "206818165", "ТЕСТ МЧЖ", "Товар", paid, ship, debt)

    @Test
    fun statuses_follow_1c_sign_convention() {
        val file = report(
            listOf(
                r("1", "", "100", "-100"),    // ничего не оплачено
                r("2", "40", "100", "-60"),   // оплачено частично
                r("3", "100", "100", "0"),    // оплачено полностью
                r("4", "101", "100", "1"),    // переплата
            )
        )
        val result = ExcelImportService().parseDebtorExcel(file)
        file.delete()

        val byDoc = result.shipments.associateBy { it.docNumber }
        assertEquals(ShipmentStatus.AWAITING_PAYMENT, byDoc.getValue("1").status)
        assertEquals(ShipmentStatus.PARTIALLY_PAID, byDoc.getValue("2").status)
        assertEquals(ShipmentStatus.PAID, byDoc.getValue("3").status)
        assertEquals(ShipmentStatus.PAID, byDoc.getValue("4").status)
    }

    @Test
    fun raw_import_rows_skip_total_row_and_keep_columns() {
        val file = report(
            listOf(
                r("1", "", "100", "-100"),
                listOf("", "", "", "", "", "", "", "", "-100"), // итоговая строка 1С
            )
        )
        val rows = ExcelImportService().readRawImportRows(file)
        file.delete()

        assertEquals(1, rows.size)
        assertEquals(8, rows[0].size)                  // A..H
        assertEquals("ТЕСТ МЧЖ", rows[0][3])           // Тип / Контрагент
        assertEquals("-100", rows[0][7])               // Долг как есть
    }

    private fun reportNew(rows: List<List<String>>): File {
        val header = listOf("МП", "Док номер", "Дата составления", "", "Док номер.Статус", "Приход", "Продажа", "Долг", "")
        return reportWithHeader(header, rows)
    }

    private fun reportWithHeader(header: List<String>, rows: List<List<String>>): File {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("r")
        (listOf(header) + rows).forEachIndexed { i, cells ->
            val row = sheet.createRow(i)
            cells.forEachIndexed { c, v -> row.createCell(c).setCellValue(v) }
        }
        val f = File.createTempFile("debt_new_", ".xls")
        FileOutputStream(f).use { wb.write(it) }
        wb.close()
        return f
    }

    // Новый формат: нет колонки ИНН, колонки Приход / Продажа / Долг
    private fun n(doc: String, who: String, note: String, paid: String, ship: String, debt: String) =
        listOf("Нафиса офис", doc, "28.06.2026", who, note, paid, ship, debt, "")

    @Test
    fun new_layout_statuses_and_columns() {
        val file = reportNew(
            listOf(
                n("1", "ОПТОМ II", "Клиент А ИНН 305225561", "", "100", "-100"),
                n("2", "ОПТОМ II", "Клиент Б 2 вариант", "40", "100", "-60"),
                n("3", "Клиент В", "Клиент В Нафиса опа", "100", "100", "0"),
                n("4", "ОПТОМ I", "Клиент Г", "101", "100", "1"),
            )
        )
        val service = ExcelImportService()
        val result = service.parseDebtorExcel(file)
        val raw = service.readRawImportRows(file)
        file.delete()

        val byDoc = result.shipments.associateBy { it.docNumber }
        assertEquals(ShipmentStatus.AWAITING_PAYMENT, byDoc.getValue("1").status)
        assertEquals(ShipmentStatus.PARTIALLY_PAID, byDoc.getValue("2").status)
        assertEquals(ShipmentStatus.PAID, byDoc.getValue("3").status)
        assertEquals(ShipmentStatus.PAID, byDoc.getValue("4").status)

        // Импорт: порядок колонок листа A..H, суммы на своих местах
        assertEquals(4, raw.size)
        assertEquals("ОПТОМ II", raw[0][3])
        assertEquals("Клиент А ИНН 305225561", raw[0][4])
        assertEquals(listOf("40", "100", "-60"), raw[1].subList(5, 8))
    }

    // Данные напрямую из регистра 1С, считаются как отчёт «Дебиторка»
    @Test
    fun com_rows_like_1c_report() {
        val bridge = OnecComBridge {}
        val rows = listOf(
            // долг с прошлого года 10, в этом году продано 100, оплачено 40 -> на конец -70
            OnecComRow(doc = "1", kontr = "ОПТОМ II", note = "Клиент", inAll = 40.0, outAll = 110.0, inYear = 40.0, outYear = 100.0),
            OnecComRow(doc = "2", kontr = "Закрыт", note = "Клиент 2", inAll = 50.0, outAll = 50.0, inYear = 50.0, outYear = 50.0),
        )
        val chosen = bridge.chooseReport(rows)
        assertEquals(1, chosen.rows.size)                 // закрытый договор не выводится
        val r = chosen.rows.single()
        assertEquals(-70.0, r.debt, 0.001)               // «На конец», минус = должен
        assertEquals(100.0, r.sale, 0.001)               // «Расход» за год
        assertEquals(40.0, r.paid, 0.001)                // «Приход» за год

        // сквозная проверка: данные 1С -> статусы и строки для «Импорт»
        val file = bridge.writeReportFile(chosen.rows)
        val service = ExcelImportService()
        val parsed = service.parseDebtorExcel(file)
        val raw = service.readRawImportRows(file)
        file.delete()
        assertEquals(ShipmentStatus.PARTIALLY_PAID, parsed.shipments.single().status)
        assertEquals("ОПТОМ II", raw.single()[3])
        assertEquals(listOf("40", "100", "-70"), raw.single().subList(5, 8))
    }
}
