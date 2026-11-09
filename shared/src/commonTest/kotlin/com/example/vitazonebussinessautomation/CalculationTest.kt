package com.example.vitazonebussinessautomation

import com.example.vitazonebussinessautomation.domain.models.*
import com.example.vitazonebussinessautomation.domain.service.DebtCalculator
import com.example.vitazonebussinessautomation.domain.service.DocumentValidator
import com.example.vitazonebussinessautomation.domain.service.ValidationResult
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalculationTest {

    private val calculator = DebtCalculator()
    private val validator = DocumentValidator()

    @Test
    fun testDueDateCalculation() {
        val shipment = Shipment(
            id = "s1",
            date = LocalDate.parse("2024-10-01"),
            delayDays = 15,
            inn = "303303592",
            companyName = "Test Co",
            docNumber = "DOC-101",
            amount = 10000.0
        )
        assertEquals(LocalDate.parse("2024-10-16"), shipment.dueDate)
    }

    @Test
    fun testReturnDeductionAndStatusRecalculation() {
        val shipments = listOf(
            Shipment(
                id = "s1",
                date = LocalDate.parse("2024-10-01"),
                delayDays = 10,
                inn = "303303592",
                companyName = "Test Co",
                docNumber = "DOC-100",
                amount = 10000.0,
                variant = "Перечисление"
            ),
            Shipment(
                id = "s2",
                date = LocalDate.parse("2024-10-05"),
                delayDays = 10,
                inn = "303303592",
                companyName = "Test Co",
                docNumber = "DOC-100",
                amount = 3000.0,
                status = ShipmentStatus.RETURN,
                variant = "Перечисление"
            )
        )

        val payments = listOf(
            Payment(
                id = "p1",
                docNumber = "DOC-100",
                date = LocalDate.parse("2024-10-06"),
                inn = "303303592",
                companyName = "Test Co",
                amount = 7000.0,
                status = PaymentStatus.PAID,
                variant = "Перечисление"
            )
        )

        val result = calculator.calculate(shipments, payments)

        // Effective amount for DOC-100 should be 10000 - 3000 = 7000
        // Paid amount = 7000 -> Debt = 0, Status = PAID
        val debtRec = result.debtRecords.first { it.docNumber == "DOC-100" }
        assertEquals(10000.0, debtRec.originalAmount)
        assertEquals(3000.0, debtRec.returnAmount)
        assertEquals(7000.0, debtRec.effectiveAmount)
        assertEquals(7000.0, debtRec.paidAmount)
        assertEquals(0.0, debtRec.debtAmount)
        assertEquals(ShipmentStatus.PAID, debtRec.status)
    }

    @Test
    fun testDirectPaymentMatchingAndPoolDistribution() {
        val shipments = listOf(
            Shipment(
                id = "s1",
                date = LocalDate.parse("2024-10-01"),
                delayDays = 10,
                inn = "313422013",
                companyName = "Company B",
                docNumber = "INV-1",
                amount = 5000.0,
                variant = "Терминал"
            ),
            Shipment(
                id = "s2",
                date = LocalDate.parse("2024-10-02"),
                delayDays = 10,
                inn = "313422013",
                companyName = "Company B",
                docNumber = "INV-2",
                amount = 10000.0,
                variant = "Терминал"
            )
        )

        // Payment B matches INV-2 directly for 10,000
        val payments = listOf(
            Payment(
                id = "p1",
                docNumber = "INV-2",
                date = LocalDate.parse("2024-10-03"),
                inn = "313422013",
                companyName = "Company B",
                amount = 10000.0,
                status = PaymentStatus.PAID,
                variant = "Терминал"
            )
        )

        val result = calculator.calculate(shipments, payments)

        val inv1Rec = result.debtRecords.first { it.docNumber == "INV-1" }
        val inv2Rec = result.debtRecords.first { it.docNumber == "INV-2" }

        assertEquals(ShipmentStatus.AWAITING_PAYMENT, inv1Rec.status)
        assertEquals(5000.0, inv1Rec.debtAmount)

        assertEquals(ShipmentStatus.PAID, inv2Rec.status)
        assertEquals(0.0, inv2Rec.debtAmount)
    }

    @Test
    fun testDocumentValidator() {
        val shipments = listOf(
            Shipment(
                id = "s1",
                date = LocalDate.parse("2024-10-01"),
                delayDays = 10,
                inn = "303303592",
                companyName = "Test Co",
                docNumber = "DOC-500",
                amount = 10000.0,
                status = ShipmentStatus.AWAITING_PAYMENT
            )
        )

        // Duplicate non-return document should fail
        val candidateDup = Shipment(
            id = "s2",
            date = LocalDate.parse("2024-10-02"),
            delayDays = 10,
            inn = "303303592",
            companyName = "Test Co",
            docNumber = "DOC-500",
            amount = 5000.0,
            status = ShipmentStatus.AWAITING_PAYMENT
        )

        val res1 = validator.validateShipmentDoc(candidateDup, shipments)
        assertTrue(res1 is ValidationResult.Invalid)

        // Return for existing document should succeed
        val candidateReturn = Shipment(
            id = "s3",
            date = LocalDate.parse("2024-10-02"),
            delayDays = 10,
            inn = "303303592",
            companyName = "Test Co",
            docNumber = "DOC-500",
            amount = 2000.0,
            status = ShipmentStatus.RETURN
        )

        val res2 = validator.validateShipmentDoc(candidateReturn, shipments)
        assertTrue(res2 is ValidationResult.Valid)
    }
}
