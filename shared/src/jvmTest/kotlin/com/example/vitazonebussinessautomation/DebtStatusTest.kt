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

    // Данные из отчёта «Дебиторка» уходят в «Импорт» как есть, без пересчёта
    @Test
    fun report_rows_go_to_import_as_is() {
        val bridge = OnecComBridge {}
        val rows = listOf(
            OnecComRow(mp = "Нафиса офис", doc = "1", date = "28.06.2026", kontr = "ОПТОМ II",
                note = "Клиент", prihod = 40.0, rashod = 100.0, dolg = -70.0),
            OnecComRow(mp = "Зокир МП", doc = "2", date = "28.06.2026", kontr = "Закрыт",
                note = "Клиент 2", prihod = 50.0, rashod = 50.0, dolg = 0.0),
        )
        val out = bridge.toReportRows(rows)
        assertEquals(2, out.size)                 // ни одна строка отчёта не выбрасывается
        assertEquals(-70.0, out[0].debt, 0.001)
        assertEquals(100.0, out[0].sale, 0.001)
        assertEquals(40.0, out[0].paid, 0.001)

        val file = bridge.writeReportFile(out)
        val raw = ExcelImportService().readRawImportRows(file)
        file.delete()
        assertEquals(2, raw.size)
        assertEquals("Нафиса офис", raw[0][0])
        assertEquals("ОПТОМ II", raw[0][3])
        assertEquals(listOf("40", "100", "-70"), raw[0].subList(5, 8))
    }
}
