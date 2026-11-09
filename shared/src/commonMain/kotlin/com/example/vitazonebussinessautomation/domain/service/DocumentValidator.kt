package com.example.vitazonebussinessautomation.domain.service

import com.example.vitazonebussinessautomation.domain.models.Shipment
import com.example.vitazonebussinessautomation.domain.models.ShipmentStatus

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
}

class DocumentValidator {
    fun validateShipmentDoc(
        shipmentToValidate: Shipment,
        allShipments: List<Shipment>
    ): ValidationResult {
        val doc = shipmentToValidate.docNumber.trim()
        if (doc.isBlank()) return ValidationResult.Valid

        val isReturn = shipmentToValidate.status == ShipmentStatus.RETURN

        val otherShipments = allShipments.filter { it.id != shipmentToValidate.id }

        val hasOriginalShipment = otherShipments.any { other ->
            other.docNumber.trim().equals(doc, ignoreCase = true) &&
                    other.status != ShipmentStatus.RETURN &&
                    other.status != ShipmentStatus.CANCELLED
        }

        return when {
            isReturn && !hasOriginalShipment -> {
                ValidationResult.Invalid("Такого номера документа нет: '$doc'. Для создания возврата должен существовать исходный документ.")
            }
            !isReturn && hasOriginalShipment -> {
                ValidationResult.Invalid("Номер документа '$doc' уже существует и не может повторяться. Чтобы создать возврат, выберите статус «Возврат».")
            }
            else -> ValidationResult.Valid
        }
    }
}
