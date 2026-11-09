package com.example.vitazonebussinessautomation.domain.service

import com.example.vitazonebussinessautomation.domain.models.BankStatementItem
import kotlinx.datetime.LocalDate
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.math.BigDecimal

data class BankDailyImportResult(
    val items: List<BankStatementItem>,
    val totalRows: Int,
    val totalDebit: Double,
    val totalCredit: Double,
    val errors: List<String>
)

class BankDailyImportService {

    fun parseBankStatementExcel(file: File): BankDailyImportResult {
        val items = mutableListOf<BankStatementItem>()
        val errors = mutableListOf<String>()
        val defaultDate = LocalDate(2026, 10, 23)

        if (!file.exists()) {
            return BankDailyImportResult(emptyList(), 0, 0.0, 0.0, listOf("Файл выписки не найден: ${file.absolutePath}"))
        }

        try {
            val workbook = WorkbookFactory.create(file)
            val sheet = workbook.getSheetAt(0)

            val lastRow = sheet.lastRowNum
            var dataStartRow = 4

            // Find header row "Дата проводки" or "Дата"
            for (r in 0..Math.min(10, lastRow)) {
                val row = sheet.getRow(r) ?: continue
                val col0 = getCellValueAsString(row.getCell(0)).lowercase()
                if (col0.contains("дата")) {
                    dataStartRow = r + 1
                    break
                }
            }

            for (r in dataStartRow..lastRow) {
                val row = sheet.getRow(r) ?: continue

                val col0 = getCellValueAsString(row.getCell(0))
                if (col0.contains("Обороты") || col0.contains("Исходящий остаток")) {
                    break
                }

                val rawDate = col0
                val parsedDate = parseDateString(rawDate, row.getCell(0)) ?: defaultDate

                val docNumber = cleanDocNumber(getCellValueAsString(row.getCell(1)))
                val mfo = getCellValueAsString(row.getCell(2))
                val account = getCellValueAsString(row.getCell(3))
                val companyName = cleanCompanyName(getCellValueAsString(row.getCell(4)))
                val inn = cleanInn(getCellValueAsString(row.getCell(5)))
                val details = getCellValueAsString(row.getCell(6))
                val debit = getCellValueAsDouble(row.getCell(7))
                val credit = getCellValueAsDouble(row.getCell(8))

                if (docNumber.isBlank() && debit == 0.0 && credit == 0.0 && companyName.isBlank()) {
                    continue
                }

                val item = BankStatementItem(
                    id = "bank-${r}-${System.currentTimeMillis()}",
                    date = parsedDate,
                    docNumber = docNumber,
                    mfo = mfo,
                    account = account,
                    companyName = companyName,
                    inn = inn,
                    details = details,
                    debit = debit,
                    credit = credit
                )

                items.add(item)
            }

            workbook.close()
        } catch (e: Exception) {
            errors.add("Ошибка парсинга банковской выписки: ${e.message}")
        }

        val totalDebit = items.sumOf { it.debit }
        val totalCredit = items.sumOf { it.credit }

        return BankDailyImportResult(
            items = items,
            totalRows = items.size,
            totalDebit = totalDebit,
            totalCredit = totalCredit,
            errors = errors
        )
    }

    private fun cleanCompanyName(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length > 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        return cleaned
    }

    private fun cleanInn(raw: String): String {
        val digits = raw.replace("\\D".toRegex(), "")
        return if (digits.length >= 9) digits else raw.trim()
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

        val cleanStr = raw.trim().substringBefore(" ")
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
