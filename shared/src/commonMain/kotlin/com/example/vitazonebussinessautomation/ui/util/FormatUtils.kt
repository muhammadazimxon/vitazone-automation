package com.example.vitazonebussinessautomation.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.domain.models.PaymentStatus
import com.example.vitazonebussinessautomation.domain.models.ShipmentStatus
import kotlinx.datetime.LocalDate
import kotlin.math.abs

object FormatUtils {
    fun formatCurrency(amount: Double): String {
        val intPart = amount.toLong()
        val formattedInt = intPart.toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$formattedInt сум"
    }

    fun formatDate(date: LocalDate): String {
        val day = date.dayOfMonth.toString().padStart(2, '0')
        val month = date.monthNumber.toString().padStart(2, '0')
        val year = date.year
        return "$day.$month.$year"
    }
}

@Composable
fun StatusBadge(status: ShipmentStatus) {
    val (bgColor, textColor) = when (status) {
        ShipmentStatus.PAID -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        ShipmentStatus.PARTIALLY_PAID -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        ShipmentStatus.AWAITING_PAYMENT -> Color(0xFFFFEBEE) to Color(0xFFC62828)
        ShipmentStatus.RETURN -> Color(0xFFF3E5F5) to Color(0xFF6A1B9A)
        ShipmentStatus.CANCELLED -> Color(0xFFECEFF1) to Color(0xFF455A64)
    }

    Text(
        text = status.displayText,
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
fun PaymentStatusBadge(status: PaymentStatus) {
    val (bgColor, textColor) = when (status) {
        PaymentStatus.PAID -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        PaymentStatus.AWAITING -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        PaymentStatus.CANCELLED -> Color(0xFFECEFF1) to Color(0xFF455A64)
    }

    Text(
        text = status.displayText,
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
