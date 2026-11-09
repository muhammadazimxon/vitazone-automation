package com.example.vitazonebussinessautomation

import com.example.vitazonebussinessautomation.domain.service.ExcelImportService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ExcelParseTest {

    @Test
    fun parseSampleExcelWithService() {
        val file = File("/Users/macbookpro/AndroidStudioProjects/VitazoneBussinessAutomation/дебиторка 25.10.2026.xls")
        if (!file.exists()) {
            println("Excel file not found!")
            return
        }

        val service = ExcelImportService()
        val result = service.parseDebtorExcel(file)

        println("Parsed shipments count: ${result.shipments.size}")
        println("Parsed payments count: ${result.payments.size}")
        println("Total shipment amount: ${result.totalShipmentAmount}")
        println("Total paid amount: ${result.totalPaidAmount}")
        println("Total remaining debt: ${result.totalRemainingDebt}")

        assertTrue(result.errors.isEmpty(), "Expected 0 errors during Excel parsing")
        assertTrue(result.shipments.isNotEmpty(), "Expected parsed shipments to be non-empty")
        assertTrue(result.payments.isNotEmpty(), "Expected parsed payments to be non-empty")
    }
}
