package com.example.vitazonebussinessautomation.domain.service

import com.example.vitazonebussinessautomation.domain.models.Payment
import com.example.vitazonebussinessautomation.domain.models.PaymentStatus
import com.example.vitazonebussinessautomation.domain.models.Shipment
import com.example.vitazonebussinessautomation.domain.models.ShipmentStatus
import kotlinx.datetime.LocalDate
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.math.BigDecimal

data class ExcelImportResult(
    val shipments: List<Shipment>,
    val payments: List<Payment> = emptyList(),
    val totalRows: Int,
    val totalShipmentAmount: Double,
    val totalPaidAmount: Double,
    val totalRemainingDebt: Double,
    val errors: List<String>
)

class ExcelImportService {

    fun parseDebtorExcel(file: File): ExcelImportResult {
        val shipments = mutableListOf<Shipment>()
        val payments = mutableListOf<Payment>()
        val errors = mutableListOf<String>()
        val today = java.time.LocalDate.now().let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }

        if (!file.exists()) {
            return ExcelImportResult(emptyList(), emptyList(), 0, 0.0, 0.0, 0.0, listOf("Файл не найден: ${file.absolutePath}"))
        }

        try {
            val workbook = WorkbookFactory.create(file)
            val sheet = workbook.getSheetAt(0)
            val layout = detectLayout(sheet.getRow(0))

            val lastRow = sheet.lastRowNum
            for (r in 1..lastRow) {
                val row = sheet.getRow(r) ?: continue

                // Col 0: Менеджер / МП
                val manager = getCellValueAsString(cellAt(row, layout.mp))

                // Col 1: Док номер
                val rawDoc = getCellValueAsString(cellAt(row, layout.doc))
                val docNumber = cleanDocNumber(rawDoc)

                // Col 2: Дата составления
                val rawDate = getCellValueAsString(cellAt(row, layout.date))
                val parsedDate = parseDateString(rawDate, cellAt(row, layout.date)) ?: today

                // Col 3: ИНН / Код
                var inn = getCellValueAsString(cellAt(row, layout.inn))

                // Col 4 & Col 5: Контрагент и Вариант
                val col4Val = getCellValueAsString(cellAt(row, layout.c4))
                val col5Val = getCellValueAsString(cellAt(row, layout.c5))

                val companyName: String
                val variant: String

                if (col4Val.contains("вариант", ignoreCase = true)) {
                    variant = cleanVariant(col4Val)
                    companyName = cleanCompanyName(col5Val)
                } else {
                    companyName = cleanCompanyName(col4Val.ifBlank { col5Val })
                    variant = if (col5Val.contains("вариант", ignoreCase = true)) cleanVariant(col5Val) else "Вариант 1"
                }

                // Col 6: Оплачено
                val paidAmt = getCellValueAsDouble(cellAt(row, layout.paid))

                // Col 7: Сумма отгрузки
                val shipmentAmt = getCellValueAsDouble(cellAt(row, layout.ship))

                // Col 8: Остаток долга
                val debtAmt = getCellValueAsDouble(cellAt(row, layout.debt))

                if (docNumber.isBlank() && shipmentAmt == 0.0 && debtAmt == 0.0 && paidAmt == 0.0) {
                    continue
                }

                // Check if companyName or inn contains a real INN
                val extractedInn = extractInnFromNoteOrCode(col5Val) ?: extractInnFromNoteOrCode(col4Val) ?: extractInnFromNoteOrCode(inn)
                if (extractedInn != null && (inn.startsWith("77777") || inn.isBlank() || inn.length < 9)) {
                    inn = extractedInn
                }

                // В отчёте 1С долг отрицательный (остаток = оплачено − отгрузка), поэтому
                // сумма, которую клиент ещё должен, это -debtAmt. Раньше отрицательный остаток
                // считался «нет долга», и все отгрузки получали статус «Оплачено».
                val remainingDebt = -debtAmt
                val calculatedStatus = when {
                    remainingDebt <= 0.01 && shipmentAmt > 0 -> ShipmentStatus.PAID
                    paidAmt > 0.01 -> ShipmentStatus.PARTIALLY_PAID
                    else -> ShipmentStatus.AWAITING_PAYMENT
                }

                if (shipmentAmt > 0) {
                    val shipment = Shipment(
                        id = "excel-${r}-${System.currentTimeMillis()}",
                        date = parsedDate,
                        delayDays = 1,
                        inn = inn,
                        companyName = companyName,
                        docNumber = docNumber,
                        amount = shipmentAmt,
                        status = calculatedStatus,
                        note = col5Val,
                        variant = variant,
                        manager = manager
                    )
                    shipments.add(shipment)
                }

                if (paidAmt > 0) {
                    val payment = Payment(
                        id = "pay-${r}-${System.currentTimeMillis()}",
                        docNumber = docNumber,
                        date = parsedDate,
                        inn = inn,
                        companyName = companyName,
                        amount = paidAmt,
                        status = PaymentStatus.PAID,
                        variant = variant
                    )
                    payments.add(payment)
                }
            }

            workbook.close()
        } catch (e: Exception) {
            errors.add("Ошибка парсинга файла Excel: ${e.message}")
        }

        val finalShipments = shipments
        val finalPayments = payments

        val sumShipments = finalShipments.sumOf { it.amount }
        val sumPaid = finalPayments.sumOf { it.amount }
        val sumDebt = finalShipments.filter { it.status != ShipmentStatus.PAID }.sumOf { it.amount }

        return ExcelImportResult(
            shipments = finalShipments,
            payments = finalPayments,
            totalRows = finalShipments.size,
            totalShipmentAmount = sumShipments,
            totalPaidAmount = sumPaid,
            totalRemainingDebt = sumDebt,
            errors = errors
        )
    }

    /**
     * Раскладка колонок отчёта 1С. Поддерживаются два формата (индекс -1 = колонки нет):
     *  - старый: МП | Док номер | Дата | ИНН | Контрагент | Док номер.Назначение | Сум | Сум | Сум
     *  - новый:  МП | Док номер | Дата | Контрагент (без заголовка) | Док номер.Статус | Приход | Продажа | Долг
     * В обоих: остаток/долг = оплачено − отгрузка (долг отрицательный).
     */
    private data class Layout(
        val mp: Int, val doc: Int, val date: Int, val inn: Int,
        val c4: Int, val c5: Int, val paid: Int, val ship: Int, val debt: Int
    )

    private val oldLayout = Layout(0, 1, 2, 3, 4, 5, 6, 7, 8)

    private fun detectLayout(header: Row?): Layout {
        if (header == null) return oldLayout
        val h = (0 until maxOf(header.lastCellNum.toInt(), 0))
            .map { getCellValueAsString(header.getCell(it)).trim().lowercase() }
        fun find(vararg keys: String) = h.indexOfFirst { it in keys }

        val ship = find("продажа", "отгрузка")
        val debt = find("долг", "остаток")
        if (ship < 0 || debt < 0) return oldLayout   // заголовки Сум/Сум/Сум — старый формат

        val date = find("дата составления", "дата").let { if (it < 0) 2 else it }
        val inn = find("инн")
        val c4 = h.indexOfFirst { it.contains("контрагент") && !it.startsWith("док номер") }.let {
            if (it >= 0) it else if (h.getOrNull(date + 1).isNullOrBlank()) date + 1 else -1
        }
        val c5 = find("док номер.назначение", "док номер.статус", "назначение").let {
            if (it >= 0) it else if (c4 >= 0) c4 + 1 else -1
        }
        return Layout(
            mp = find("мп").let { if (it < 0) 0 else it },
            doc = find("док номер").let { if (it < 0) 1 else it },
            date = date, inn = inn, c4 = c4, c5 = c5,
            paid = find("приход", "оплачено", "оплата"),
            ship = ship, debt = debt
        )
    }

    private fun cellAt(row: Row, idx: Int): Cell? = if (idx < 0) null else row.getCell(idx)

    /**
     * Сырые строки отчёта 1С для листа "Импорт" в порядке колонок листа A..H независимо от формата 1С:
     * МП | Док номер | Дата составления | Тип / Контрагент | Док номер.Статус | Приход | Продажа | Долг.
     * (I — пустая, J «Отсрочка» вводится вручную, дата отчёта пишется отдельно.)
     * Итоговая строка 1С (без документа и названий) пропускается.
     */
    fun readRawImportRows(file: File): List<List<String>> {
        val out = mutableListOf<List<String>>()
        try {
            WorkbookFactory.create(file).use { wb ->
                val sheet = wb.getSheetAt(0)
                val layout = detectLayout(sheet.getRow(0))
                for (r in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(r) ?: continue
                    fun str(i: Int) = getCellValueAsString(cellAt(row, i))
                    val doc = cleanDocNumber(str(layout.doc))
                    val c4 = str(layout.c4)
                    val c5 = str(layout.c5)
                    if (doc.isBlank() && c4.isBlank() && c5.isBlank()) continue
                    out.add(
                        listOf(
                            str(layout.mp), doc, str(layout.date), c4, c5,
                            rawAmount(cellAt(row, layout.paid)),
                            rawAmount(cellAt(row, layout.ship)),
                            rawAmount(cellAt(row, layout.debt))
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun rawAmount(cell: Cell?): String {
        if (cell == null || cell.cellType == CellType.BLANK) return ""
        if (cell.cellType == CellType.STRING && cell.stringCellValue.isBlank()) return ""
        val d = getCellValueAsDouble(cell)
        return if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.US, "%.2f", d)
    }

    private fun cleanCompanyName(raw: String): String {
        var cleaned = raw.trim()
        
        // Remove manager suffixes like Нафиса опа, Нафисапа, Нафиса опа 100%, Нафиса, Шахзод, Зокир, Лазиз
        val managerJunkRegex = Regex("(?i)\\s*(нафиса\\s*опа|нафиса\\s*па|нафиса|шахзод|зокир|лазиз|нафисаопа|нафисаофис).*")
        cleaned = managerJunkRegex.replace(cleaned, "").trim()

        // Remove variant & percent suffixes like "2 вариант 100%", "2 вариант", "100%", "инн ..."
        val variantJunkRegex = Regex("(?i)\\s*(\\d+\\s*вариант|100%|инн\\s*\\d+).*")
        cleaned = variantJunkRegex.replace(cleaned, "").trim()

        // Explicit cleanup for remaining "2 вариант" / "100%"
        cleaned = cleaned.replace(Regex("(?i)\\s*2\\s*вариант.*"), "")
            .replace(Regex("(?i)\\s*1\\s*вариант.*"), "")
            .replace(Regex("(?i)\\s*100%.*"), "")
            .trim()

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length > 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        return cleaned
    }

    private fun cleanVariant(raw: String): String {
        val lower = raw.trim().lowercase()
        return if (lower.contains("2") || lower.contains("вариант 2")) {
            "Вариант 2"
        } else {
            "Вариант 1"
        }
    }

    private fun extractInnFromNoteOrCode(text: String): String? {
        if (text.isBlank()) return null
        val matchInnKeyword = Regex("(?i)инн\\s*(\\d{9,14})").find(text)
        if (matchInnKeyword != null) {
            return matchInnKeyword.groupValues[1]
        }
        val match9Digit = Regex("\\b(\\d{9})\\b").find(text)
        if (match9Digit != null) {
            return match9Digit.groupValues[1]
        }
        return null
    }

    private fun cleanDocNumber(raw: String): String {
        var str = raw.trim()
        if (str.endsWith(".0")) {
            str = str.substringBefore(".0")
        }
        return str
    }

    private fun getCellValueAsString(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    val date = cell.dateCellValue
                    val year = date.year + 1900
                    val month = (date.month + 1).toString().padStart(2, '0')
                    val day = date.date.toString().padStart(2, '0')
                    "$day.$month.$year"
                } else {
                    val doubleVal = cell.numericCellValue
                    val bigDec = BigDecimal(doubleVal)
                    if (doubleVal == doubleVal.toLong().toDouble()) {
                        doubleVal.toLong().toString()
                    } else {
                        bigDec.toPlainString()
                    }
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue.trim()
                } catch (e: Exception) {
                    cell.numericCellValue.toLong().toString()
                }
            }
            else -> ""
        }
    }

    private fun getCellValueAsDouble(cell: Cell?): Double {
        if (cell == null) return 0.0
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> {
                val clean = cell.stringCellValue.replace(Regex("[\\s\\u00A0]"), "").replace(",", ".")
                clean.toDoubleOrNull() ?: 0.0
            }
            CellType.FORMULA -> {
                try {
                    when (cell.cachedFormulaResultType) {
                        CellType.NUMERIC -> cell.numericCellValue
                        CellType.STRING -> {
                            val clean = cell.stringCellValue.replace(Regex("[\\s\\u00A0]"), "").replace(",", ".")
                            clean.toDoubleOrNull() ?: 0.0
                        }
                        else -> 0.0
                    }
                } catch (e: Exception) {
                    try {
                        cell.numericCellValue
                    } catch (ex: Exception) {
                        0.0
                    }
                }
            }
            else -> 0.0
        }
    }

    private fun parseDateString(raw: String, cell: Cell?): LocalDate? {
        if (cell != null && cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            val d = cell.dateCellValue
            val year = d.year + 1900
            val month = d.month + 1
            val day = d.date
            return LocalDate(year, month, day)
        }

        val cleanStr = raw.trim()
        if (cleanStr.isBlank()) return null

        return try {
            val parts = cleanStr.split(".")
            if (parts.size == 3) {
                val day = parts[0].toInt()
                val month = parts[1].toInt()
                val year = parts[2].toInt()
                LocalDate(year, month, day)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
