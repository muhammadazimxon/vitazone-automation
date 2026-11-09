package com.example.vitazonebussinessautomation.domain.models

enum class ShipmentStatus(val displayText: String) {
    AWAITING_PAYMENT("Ожидает оплаты"),
    PARTIALLY_PAID("Частично оплачено"),
    PAID("Оплачено"),
    RETURN("Возврат"),
    CANCELLED("Отменено");

    companion object {
        fun fromString(value: String): ShipmentStatus {
            val clean = value.trim().lowercase()
            return when {
                clean == "оплачено" -> PAID
                clean == "частично оплачено" -> PARTIALLY_PAID
                clean == "возврат" -> RETURN
                clean == "отменено" -> CANCELLED
                else -> AWAITING_PAYMENT
            }
        }
    }
}
