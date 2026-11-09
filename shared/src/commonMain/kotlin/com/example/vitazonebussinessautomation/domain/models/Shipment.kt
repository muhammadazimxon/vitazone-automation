package com.example.vitazonebussinessautomation.domain.models

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

@Serializable
data class Shipment(
    val id: String,
    val date: LocalDate,
    val delayDays: Int,
    val inn: String,
    val companyName: String,
    val docNumber: String,
    val amount: Double,
    val status: ShipmentStatus = ShipmentStatus.AWAITING_PAYMENT,
    val note: String = "",
    val variant: String = "",
    val manager: String = ""
) {
    val dueDate: LocalDate
        get() = date.plus(delayDays, DateTimeUnit.DAY)

    val normalizedInn: String
        get() = inn.replace(Regex("\\D"), "")

    val effectiveAmount: Double
        get() = if (status == ShipmentStatus.RETURN) -kotlin.math.abs(amount) else kotlin.math.abs(amount)
}
