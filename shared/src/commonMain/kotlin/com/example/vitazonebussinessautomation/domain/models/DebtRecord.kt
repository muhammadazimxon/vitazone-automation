package com.example.vitazonebussinessautomation.domain.models

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class DebtRecord(
    val shipmentId: String,
    val date: LocalDate,
    val delayDays: Int,
    val dueDate: LocalDate,
    val inn: String,
    val companyName: String,
    val docNumber: String,
    val originalAmount: Double,
    val returnAmount: Double,
    val effectiveAmount: Double,
    val paidAmount: Double,
    val debtAmount: Double,
    val status: ShipmentStatus,
    val note: String,
    val variant: String,
    val manager: String
) {
    val isOverdue: Boolean
        get() = debtAmount > 0.01 && status != ShipmentStatus.PAID && status != ShipmentStatus.CANCELLED
}
