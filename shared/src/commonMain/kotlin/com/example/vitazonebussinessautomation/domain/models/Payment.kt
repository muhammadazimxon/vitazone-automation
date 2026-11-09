package com.example.vitazonebussinessautomation.domain.models

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus(val displayText: String) {
    PAID("Оплачено"),
    AWAITING("Ожидает"),
    CANCELLED("Отменено");

    companion object {
        fun fromString(value: String): PaymentStatus {
            val clean = value.trim().lowercase()
            return when {
                clean == "оплачено" -> PAID
                clean == "отменено" -> CANCELLED
                else -> AWAITING
            }
        }
    }
}

@Serializable
data class Payment(
    val id: String,
    val docNumber: String = "",
    val date: LocalDate,
    val inn: String,
    val companyName: String,
    val amount: Double,
    val status: PaymentStatus = PaymentStatus.PAID,
    val variant: String = ""
) {
    val normalizedInn: String
        get() = inn.replace(Regex("\\D"), "")
}
